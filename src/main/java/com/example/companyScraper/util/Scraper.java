package com.example.companyScraper.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Scraper {

    public enum ProxyRotationStrategy { NONE, RANDOM, ROUND_ROBIN, SMART }
    public enum ConnectionType { DIRECT, PROXY }

    private final List<ProxyInfo> proxies = Collections.synchronizedList(new ArrayList<>());
    private final ProxyRotationStrategy strategy;
    private final ConnectionType connectionType;
    private final AtomicInteger rrIndex = new AtomicInteger(0);
    private final Map<String, Long> domainLastAccess = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Integer> domainRequestCount = Collections.synchronizedMap(new HashMap<>());
    private final Map<ProxyInfo, ProxyStats> proxyStats = Collections.synchronizedMap(new HashMap<>());
    
    // Enhanced configuration
    private final long minDelayBetweenRequests;
    private final long domainCooldownMs;
    private final int maxRetriesPerProxy;
    private final boolean useDirectConnection;

    // Expanded and more realistic user agents
    private static final List<String> USER_AGENTS = Arrays.asList(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    );

    // Common headers to appear more like a real browser
    private static final Map<String, String> DEFAULT_HEADERS = new HashMap<>();
    static {
        DEFAULT_HEADERS.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
        DEFAULT_HEADERS.put("Accept-Language", "en-US,en;q=0.9");
        DEFAULT_HEADERS.put("Accept-Encoding", "gzip, deflate, br");
        DEFAULT_HEADERS.put("DNT", "1");
        DEFAULT_HEADERS.put("Connection", "keep-alive");
        DEFAULT_HEADERS.put("Upgrade-Insecure-Requests", "1");
        DEFAULT_HEADERS.put("Sec-Fetch-Dest", "document");
        DEFAULT_HEADERS.put("Sec-Fetch-Mode", "navigate");
        DEFAULT_HEADERS.put("Sec-Fetch-Site", "none");
        DEFAULT_HEADERS.put("Sec-Fetch-User", "?1");
        DEFAULT_HEADERS.put("Cache-Control", "max-age=0");
    }

    public Scraper(String proxyFile, boolean useProxies, ProxyRotationStrategy strategy) {
        this(proxyFile, useProxies, strategy, 1000, 5000, 3);
    }

    public Scraper(String proxyFile, boolean useProxies, ProxyRotationStrategy strategy, 
                   long minDelayMs, long domainCooldownMs, int maxRetriesPerProxy) {
        this.strategy = strategy != null ? strategy : ProxyRotationStrategy.NONE;
        this.connectionType = useProxies ? ConnectionType.PROXY : ConnectionType.DIRECT;
        this.useDirectConnection = !useProxies;
        this.minDelayBetweenRequests = minDelayMs;
        this.domainCooldownMs = domainCooldownMs;
        this.maxRetriesPerProxy = maxRetriesPerProxy;
        
        if (useProxies && proxyFile != null && !proxyFile.isBlank()) {
            loadProxies(proxyFile);
        }
    }

    // New constructor for direct connection only
    public Scraper(long minDelayMs, long domainCooldownMs, int maxRetries) {
        this.strategy = ProxyRotationStrategy.NONE;
        this.connectionType = ConnectionType.DIRECT;
        this.useDirectConnection = true;
        this.minDelayBetweenRequests = minDelayMs;
        this.domainCooldownMs = domainCooldownMs;
        this.maxRetriesPerProxy = maxRetries;
    }

    private void loadProxies(String proxyFile) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(proxyFile));
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                try {
                    ProxyInfo proxy = parseProxyLine(line);
                    if (proxy != null) {
                        proxies.add(proxy);
                        proxyStats.put(proxy, new ProxyStats());
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse proxy line: " + line + " - " + e.getMessage());
                }
            }
            System.out.println("Loaded " + proxies.size() + " proxies from " + proxyFile);
        } catch (IOException e) {
            System.err.println("Failed to load proxies from file: " + proxyFile + " - " + e.getMessage());
        }
    }

    private ProxyInfo parseProxyLine(String line) {
        // Support formats: host:port, host:port:user:pass, user:pass@host:port
        String[] parts;
        
        if (line.contains("@")) {
            // Format: user:pass@host:port
            String[] authParts = line.split("@");
            if (authParts.length != 2) return null;
            
            String[] userPass = authParts[0].split(":");
            String[] hostPort = authParts[1].split(":");
            
            if (userPass.length == 2 && hostPort.length == 2) {
                return new ProxyInfo(hostPort[0], Integer.parseInt(hostPort[1]), userPass[0], userPass[1]);
            }
        } else {
            parts = line.split(":");
            if (parts.length == 2) {
                // Format: host:port
                return new ProxyInfo(parts[0], Integer.parseInt(parts[1]), null, null);
            } else if (parts.length == 4) {
                // Format: host:port:user:pass
                return new ProxyInfo(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3]);
            }
        }
        
        return null;
    }

    public ScrapeResponse scrapeWithRetryLogging(String url, int retries) {
        Exception lastEx = null;
        Set<ProxyInfo> triedProxies = new HashSet<>();
        
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                // Enforce rate limiting
                enforceRateLimit(url);
                
                Result result = scrape(url, triedProxies);
                return new ScrapeResponse(result, null);
                
            } catch (Exception e) {
                lastEx = e;
                System.err.println("Attempt " + (attempt + 1) + "/" + retries + " failed for " + url + ": " + e.getMessage());
                
                // Add random delay between retries
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1000, 3000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return new ScrapeResponse(new Result(url, "FAILED"), lastEx);
    }

    public Result scrape(String url) throws Exception {
        return scrape(url, new HashSet<>());
    }

    private Result scrape(String url, Set<ProxyInfo> triedProxies) throws Exception {
        String normUrl = normalizeUrl(url);
        
        // NO ROBOTS.TXT CHECKING - REMOVED COMPLETELY
        
        Document doc;
        ProxyInfo selectedProxy = useDirectConnection ? null : selectProxy(triedProxies);
        String userAgent = getRandomUserAgent();
        
        // Build connection with enhanced settings
        var connection = Jsoup.connect(normUrl)
                .userAgent(userAgent)
                .timeout(15000)
                .followRedirects(true)
                .maxBodySize(1024 * 1024 * 5) // 5MB limit
                .ignoreHttpErrors(true); // Changed to true to handle errors gracefully

        // Add realistic headers
        for (Map.Entry<String, String> header : DEFAULT_HEADERS.entrySet()) {
            connection.header(header.getKey(), header.getValue());
        }

        // Add some randomization to headers
        if (ThreadLocalRandom.current().nextBoolean()) {
            connection.header("Sec-CH-UA", getRandomChromeUA());
        }

        if (selectedProxy != null) {
            connection.proxy(selectedProxy.toProxy());
            
            // Handle proxy authentication
            if (selectedProxy.user != null && selectedProxy.pass != null) {
                String auth = Base64.getEncoder().encodeToString(
                    (selectedProxy.user + ":" + selectedProxy.pass).getBytes()
                );
                connection.header("Proxy-Authorization", "Basic " + auth);
            }
            
            triedProxies.add(selectedProxy);
        }

        try {
            doc = connection.get();
            
            // Check if we got a successful response
            if (connection.response().statusCode() != 200) {
                return new Result(normUrl, "HTTP_" + connection.response().statusCode(), 
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of(), 
                    "HTTP Error: " + connection.response().statusCode());
            }
            
            // Update proxy stats on success
            if (selectedProxy != null) {
                ProxyStats stats = proxyStats.get(selectedProxy);
                if (stats != null) {
                    stats.recordSuccess();
                }
            }
            
        } catch (Exception e) {
            // Update proxy stats on failure
            if (selectedProxy != null) {
                ProxyStats stats = proxyStats.get(selectedProxy);
                if (stats != null) {
                    stats.recordFailure();
                    
                    // If proxy has high failure rate, mark it as potentially bad
                    if (stats.getFailureRate() > 0.7 && stats.totalRequests > 10) {
                        System.err.println("Proxy " + selectedProxy.host + ":" + selectedProxy.port + 
                                         " has high failure rate: " + String.format("%.2f", stats.getFailureRate()));
                    }
                }
            }
            throw e;
        }

        String html = doc.html();

        // -------- Extract Information --------
        Set<String> emails = EmailExtractor.extractEmails(html);
        Set<String> phones = PhoneExtractor.extractPhones(html);

        // Extract phone numbers from tel: links
        for (Element el : doc.select("a[href^=tel]")) {
            String tel = el.attr("href").replace("tel:", "").trim();
            if (!tel.isEmpty()) {
                String cleanPhone = PhoneExtractor.cleanAndFormatPhone(tel);
                if (!cleanPhone.isEmpty()) {
                    phones.add(cleanPhone);
                }
            }
        }

        Set<String> linkedins = LinkedInExtractor.extractLinkedInUrls(html);
        Set<String> githubs = GitHubExtractor.extractGitHubUrls(html);
        Set<String> facebooks = FacebookExtractor.extractFacebookUrls(html);
        List<Person> people = NameRoleExtractor.extractPeopleWithAI(html, normUrl);

        // Enhanced notes with more context
        String notes = generateNotes(emails, phones, linkedins, githubs, facebooks, people, doc);

        return new Result(normUrl, "SUCCESS", emails, phones, linkedins, githubs, facebooks, people, notes);
    }

    private void enforceRateLimit(String url) throws InterruptedException {
        String domain = extractDomain(url);
        
        synchronized (domainLastAccess) {
            Long lastAccess = domainLastAccess.get(domain);
            long now = System.currentTimeMillis();
            
            if (lastAccess != null) {
                long timeSinceLastAccess = now - lastAccess;
                long waitTime = Math.max(0, domainCooldownMs - timeSinceLastAccess);
                
                if (waitTime > 0) {
                    Thread.sleep(waitTime);
                }
            }
            
            // Also enforce minimum delay between any requests
            if (minDelayBetweenRequests > 0) {
                Thread.sleep(ThreadLocalRandom.current().nextLong(minDelayBetweenRequests/2, minDelayBetweenRequests));
            }
            
            domainLastAccess.put(domain, System.currentTimeMillis());
            domainRequestCount.merge(domain, 1, Integer::sum);
        }
    }

    private String extractDomain(String url) {
        try {
            return new URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return url;
        }
    }

    // NO ROBOTS.TXT CHECKING - METHOD REMOVED COMPLETELY

    private ProxyInfo selectProxy(Set<ProxyInfo> triedProxies) {
        if (proxies.isEmpty() || strategy == ProxyRotationStrategy.NONE) return null;

        List<ProxyInfo> availableProxies = proxies.stream()
            .filter(p -> !triedProxies.contains(p))
            .filter(this::isProxyHealthy)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        if (availableProxies.isEmpty()) {
            // If all proxies have been tried, reset and try the best ones
            availableProxies = proxies.stream()
                .filter(this::isProxyHealthy)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }

        if (availableProxies.isEmpty()) return null;

        switch (strategy) {
            case RANDOM:
                return availableProxies.get(ThreadLocalRandom.current().nextInt(availableProxies.size()));
                
            case ROUND_ROBIN:
                int index = rrIndex.getAndIncrement() % availableProxies.size();
                return availableProxies.get(index);
                
            case SMART:
                // Select proxy based on success rate
                return availableProxies.stream()
                    .max(Comparator.comparing(p -> proxyStats.get(p).getSuccessRate()))
                    .orElse(availableProxies.get(0));
                    
            default:
                return null;
        }
    }

    private boolean isProxyHealthy(ProxyInfo proxy) {
        ProxyStats stats = proxyStats.get(proxy);
        if (stats == null) return true;
        
        // Consider proxy healthy if it has good success rate or hasn't been used much
        return stats.totalRequests < 5 || stats.getSuccessRate() > 0.3;
    }

    private String getRandomUserAgent() {
        return USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }

    private String getRandomChromeUA() {
        String[] chromeVersions = {"120.0.0.0", "119.0.0.0", "118.0.0.0", "117.0.0.0"};
        String version = chromeVersions[ThreadLocalRandom.current().nextInt(chromeVersions.length)];
        return "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"" + version + "\", \"Google Chrome\";v=\"" + version + "\"";
    }

    private String generateNotes(Set<String> emails, Set<String> phones, Set<String> linkedins, Set<String> githubs, Set<String> facebooks, List<Person> people, Document doc) {
        if (emails.isEmpty() && phones.isEmpty() && linkedins.isEmpty() && githubs.isEmpty() && facebooks.isEmpty() && people.isEmpty()) {
            // Try to provide more context about why no contact info was found
            if (doc.select("form").size() > 0) {
                return "No contact info found - page has forms, might require interaction";
            } else if (doc.text().toLowerCase().contains("javascript")) {
                return "No contact info found - page might be JavaScript-heavy";
            } else {
                return "No contact info found";
            }
        }

        List<String> notesParts = new ArrayList<>();
        if (!emails.isEmpty()) notesParts.add(emails.size() + " email(s)");
        if (!phones.isEmpty()) notesParts.add(phones.size() + " phone(s)");
        if (!linkedins.isEmpty()) notesParts.add(linkedins.size() + " LinkedIn profile(s)");
        if (!githubs.isEmpty()) notesParts.add(githubs.size() + " GitHub profile(s)");
        if (!facebooks.isEmpty()) notesParts.add(facebooks.size() + " Facebook profile(s)");
        if (!people.isEmpty()) notesParts.add(people.size() + " person(s) identified");

        return "Found: " + String.join(", ", notesParts);
    }

    public String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return "http://example.com";
        url = url.trim();
        
        // Handle common URL issues
        if (!url.toLowerCase().startsWith("http")) {
            url = "https://" + url;
        }
        
        // Remove fragment identifiers and common tracking parameters
        try {
            URL parsedUrl = new URL(url);
            String query = parsedUrl.getQuery();
            if (query != null) {
                // Remove common tracking parameters
                query = query.replaceAll("&?(utm_[^&]*|fbclid|gclid|ref|source)=[^&]*", "");
                query = query.replaceAll("^&+|&+$", ""); // Clean up leading/trailing &
                
                if (query.isEmpty()) {
                    url = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + parsedUrl.getPath();
                } else {
                    url = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + parsedUrl.getPath() + "?" + query;
                }
            }
        } catch (Exception e) {
            // If URL parsing fails, return as-is
        }
        
        return url;
    }

    // Getters for configuration
    public boolean isUseDirectConnection() {
        return useDirectConnection;
    }
    
    public ConnectionType getConnectionType() {
        return connectionType;
    }

    // -------- Nested Classes --------
    
    public static class ProxyInfo {
        public final String host;
        public final int port;
        public final String user;
        public final String pass;

        public ProxyInfo(String host, int port, String user, String pass) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }

        public Proxy toProxy() {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProxyInfo proxyInfo = (ProxyInfo) o;
            return port == proxyInfo.port && Objects.equals(host, proxyInfo.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port);
        }

        @Override
        public String toString() {
            return host + ":" + port + (user != null ? " (auth)" : "");
        }
    }

    private static class ProxyStats {
        private int successCount = 0;
        private int failureCount = 0;
        private int totalRequests = 0;
        private long lastUsed = 0;

        synchronized void recordSuccess() {
            successCount++;
            totalRequests++;
            lastUsed = System.currentTimeMillis();
        }

        synchronized void recordFailure() {
            failureCount++;
            totalRequests++;
            lastUsed = System.currentTimeMillis();
        }

        synchronized double getSuccessRate() {
            return totalRequests == 0 ? 1.0 : (double) successCount / totalRequests;
        }

        synchronized double getFailureRate() {
            return totalRequests == 0 ? 0.0 : (double) failureCount / totalRequests;
        }
    }

    public static class Result {
        private final String url;
        private final String status;
        private final Set<String> emails;
        private final Set<String> phones;
        private final Set<String> linkedinUrls;
        private final Set<String> githubUrls;
        private final Set<String> facebookUrls;
        private final List<Person> people;
        private final String notes;

        public Result(String url, String status) {
            this(url, status, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of(), null);
        }

        public Result(String url, String status, Set<String> emails, Set<String> phones,
                      Set<String> linkedinUrls, Set<String> githubUrls, Set<String> facebookUrls,
                      List<Person> people, String notes) {
            this.url = url;
            this.status = status;
            this.emails = emails != null ? emails : Set.of();
            this.phones = phones != null ? phones : Set.of();
            this.linkedinUrls = linkedinUrls != null ? linkedinUrls : Set.of();
            this.githubUrls = githubUrls != null ? githubUrls : Set.of();
            this.facebookUrls = facebookUrls != null ? facebookUrls : Set.of();
            this.people = people != null ? people : List.of();
            this.notes = notes;
        }

        public String getUrl() { return url; }
        public String getStatus() { return status; }
        public Set<String> getEmails() { return emails; }
        public Set<String> getPhones() { return phones; }
        public Set<String> getLinkedinUrls() { return linkedinUrls; }
        public Set<String> getGithubUrls() { return githubUrls; }
        public Set<String> getFacebookUrls() { return facebookUrls; }
        public List<Person> getPeople() { return people; }
        public String getNotes() { return notes; }
        public boolean isSuccess() { return "SUCCESS".equalsIgnoreCase(status); }
    }

    public static class ScrapeResponse {
        public final Result result;
        public final Exception error;
        
        public ScrapeResponse(Result result, Exception error) { 
            this.result = result; 
            this.error = error; 
        }
    }

    public static class Person {
        private String firstName;
        private String lastName;
        private String role;
        private String email;
        private String phone;

        public Person() {}

        public Person(String firstName, String lastName, String role) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.role = role;
        }

        // Getters and setters
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        @Override
        public String toString() {
            return String.format("%s %s (%s)", firstName, lastName, role);
        }
    }
}