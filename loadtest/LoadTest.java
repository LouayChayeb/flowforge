import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Open-model load generator for FlowForge. No dependencies; run it straight from source (Java 17+):
 *
 * <pre>
 *   java loadtest/LoadTest.java --base http://localhost:8085 --rate 20 --duration 60
 * </pre>
 *
 * It submits order-processing runs at a fixed rate whatever the response times are (so a slow system cannot
 * hide by slowing the load down), waits for every run to finish, and reports:
 * <ul>
 *   <li>end-to-end run latency (completedAt - createdAt, both server timestamps);</li>
 *   <li>throughput in runs/s and step attempts/s over the window from first submission to last completion;</li>
 *   <li>final statuses, and whether each run ended in the status its input dictates;</li>
 *   <li>the consistency checker's verdict.</li>
 * </ul>
 * The input mix is 70% normal orders, 15% declined payments (COMPENSATED) and 15% refused shipments
 * (COMPENSATED); {@code --failures 0} makes every order a normal one.
 */
public class LoadTest {

    private static final Pattern RUN_ID = Pattern.compile("\"runId\":\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z_]+)\"");
    private static final Pattern CREATED = Pattern.compile("\"createdAt\":\"([^\"]+)\"");
    private static final Pattern COMPLETED = Pattern.compile("\"completedAt\":\"([^\"]+)\"");
    private static final Pattern ATTEMPT = Pattern.compile("\"attempt\":\\d+");
    private static final Pattern CONSISTENT = Pattern.compile("\"consistent\":(true|false)");

    record Finished(String status, Instant created, Instant completed, int attempts) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String base = opts.getOrDefault("base", "http://localhost:8085");
        double rate = Double.parseDouble(opts.getOrDefault("rate", "10"));
        int duration = Integer.parseInt(opts.getOrDefault("duration", "30"));
        double failures = Double.parseDouble(opts.getOrDefault("failures", "0.3"));
        int timeout = Integer.parseInt(opts.getOrDefault("timeout", "300"));
        String workflow = opts.getOrDefault("workflow", "order-processing");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(32)).build();

        int total = (int) Math.round(rate * duration);
        System.out.printf("Submitting %d runs of %s at %.1f/s for %ds to %s%n", total, workflow, rate, duration, base);

        Map<String, String> expected = new ConcurrentHashMap<>();
        List<Long> submitMillis = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch submitted = new CountDownLatch(total);
        AtomicInteger sequence = new AtomicInteger();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        long periodNanos = (long) (1_000_000_000L / rate);
        Instant start = Instant.now();
        ticker.scheduleAtFixedRate(() -> {
            int i = sequence.getAndIncrement();
            if (i >= total) {
                return;
            }
            String[] order = order(i, failures);
            long sent = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/workflows/" + workflow + "/runs"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(order[0])).build();
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                if (error == null && response.statusCode() == 202) {
                    submitMillis.add((System.nanoTime() - sent) / 1_000_000);
                    expected.put(first(RUN_ID, response.body()), order[1]);
                } else {
                    rejected.incrementAndGet();
                }
                submitted.countDown();
            });
        }, 0, periodNanos, TimeUnit.NANOSECONDS);
        submitted.await();
        ticker.shutdownNow();
        Duration submitTime = Duration.between(start, Instant.now());
        System.out.printf("Submitted in %.1fs (%d rejected)%n", submitTime.toMillis() / 1000.0, rejected.get());

        // Wait for every run to reach a terminal status.
        Map<String, Finished> finished = new ConcurrentHashMap<>();
        Instant giveUp = Instant.now().plusSeconds(timeout);
        // Gentle polling: each GET loads a run's full history, and polling a large backlog flat out would
        // compete with the engine for the database and distort the throughput being measured.
        Semaphore inFlight = new Semaphore(4);
        while (finished.size() < expected.size() && Instant.now().isBefore(giveUp)) {
            List<String> pending = expected.keySet().stream().filter(id -> !finished.containsKey(id)).toList();
            CountDownLatch round = new CountDownLatch(pending.size());
            for (String id : pending) {
                inFlight.acquire();
                http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + id)).timeout(Duration.ofSeconds(30))
                        .build(), HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                    inFlight.release();
                    round.countDown();
                    if (error != null || response.statusCode() != 200) {
                        return;
                    }
                    String body = response.body();
                    String completed = first(COMPLETED, body);
                    if (completed != null) {
                        finished.put(id, new Finished(first(STATUS, body), Instant.parse(first(CREATED, body)),
                                Instant.parse(completed), count(ATTEMPT, body)));
                    }
                });
            }
            round.await();
            if (finished.size() < expected.size()) {
                Thread.sleep(3000);
            }
        }

        report(expected, finished, submitMillis, rate, duration);
        HttpResponse<String> consistency = http.send(HttpRequest.newBuilder(
                URI.create(base + "/api/admin/consistency?workflow=" + workflow)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("consistency:    consistent=" + first(CONSISTENT, consistency.body()));
        System.exit(0);
    }

    /** Returns {request body, expected final status}. */
    private static String[] order(int i, double failures) {
        double roll = ThreadLocalRandom.current().nextDouble();
        String amount = "120";
        String country = "FR";
        String expected = "COMPLETED";
        if (roll < failures / 2) {
            amount = "50000";          // payment declined -> inventory released
            expected = "COMPENSATED";
        } else if (roll < failures) {
            country = "XX";            // shipment refused -> refund, then release
            expected = "COMPENSATED";
        }
        String body = """
                {"input":{"orderId":"LOAD-%d","customerId":"CUS-%d","amount":%s,"country":"%s"}}"""
                .formatted(i, i % 97, amount, country);
        return new String[] {body, expected};
    }

    private static void report(Map<String, String> expected, Map<String, Finished> finished, List<Long> submitMillis,
                               double rate, int duration) {
        List<Long> latencies = finished.values().stream()
                .map(f -> Duration.between(f.created(), f.completed()).toMillis()).sorted().toList();
        Instant first = finished.values().stream().map(Finished::created).min(Instant::compareTo).orElse(Instant.now());
        Instant last = finished.values().stream().map(Finished::completed).max(Instant::compareTo).orElse(Instant.now());
        double window = Math.max(Duration.between(first, last).toMillis() / 1000.0, 0.001);
        int attempts = finished.values().stream().mapToInt(Finished::attempts).sum();
        Map<String, Integer> statuses = new TreeMap<>();
        finished.values().forEach(f -> statuses.merge(f.status(), 1, Integer::sum));
        long wrong = finished.entrySet().stream()
                .filter(e -> !e.getValue().status().equals(expected.get(e.getKey()))).count();
        List<Long> submits = new ArrayList<>(submitMillis);
        Collections.sort(submits);

        System.out.printf("offered load:   %.1f runs/s for %ds%n", rate, duration);
        System.out.printf("finished:       %d of %d (unfinished %d)%n", finished.size(), expected.size(),
                expected.size() - finished.size());
        System.out.printf("statuses:       %s, unexpected %d%n", statuses, wrong);
        System.out.printf("throughput:     %.1f runs/s, %.1f step attempts/s over %.1fs%n",
                finished.size() / window, attempts / window, window);
        System.out.printf("run latency:    p50=%dms p95=%dms p99=%dms max=%dms%n", pct(latencies, 50),
                pct(latencies, 95), pct(latencies, 99), latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1));
        System.out.printf("submit latency: p50=%dms p95=%dms p99=%dms%n", pct(submits, 50), pct(submits, 95),
                pct(submits, 99));
    }

    private static long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return opts;
    }
}
