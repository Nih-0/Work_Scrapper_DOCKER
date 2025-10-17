package com.example.companyScraper.service;

import com.example.companyScraper.model.ScrapeResult;
import com.example.companyScraper.util.*;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ScraperService {
    
    private final NameDatabaseManager nameDatabase;
    private Scraper scraper;
    private boolean useDirectConnection = true;
    private boolean extractPeople = true;
    private boolean extractSocial = true;
    private boolean extractFacebook = true;
    private int maxRetries = 3;
    private ExecutorService executorService;
    
    // Configuration
    private static final int DEFAULT_THREAD_POOL_SIZE = 5;
    private static final long DEFAULT_DELAY_MS = 1000;
    private static final long DEFAULT_DOMAIN_COOLDOWN_MS = 5000;

    public ScraperService() {
        this.nameDatabase = new NameDatabaseManager();
        this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
        initializeScraper();
    }
    
    @PostConstruct
    public void init() {
        // Load the name database on startup
        try {
            nameDatabase.loadNameDatabase("names_database.xlsx");
            NameRoleExtractor.setNameDatabase(nameDatabase);
            System.out.println("Name database initialized successfully");
        } catch (Exception e) {
            System.err.println("Failed to initialize name database: " + e.getMessage());
        }
    }
    
    private void initializeScraper() {
        this.scraper = new Scraper(
            DEFAULT_DELAY_MS, 
            DEFAULT_DOMAIN_COOLDOWN_MS, 
            maxRetries
        );
    }
    
    // Main scraping method
    public List<ScrapeResult> scrapeUrls(List<String> urls) {
        return scrapeUrls(urls, new ScrapingOptions());
    }
    
    public List<ScrapeResult> scrapeUrls(List<String> urls, ScrapingOptions options) {
        if (urls == null || urls.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Update scraper configuration based on options
        applyScrapingOptions(options);
        
        System.out.println("Starting to scrape " + urls.size() + " URLs");
        System.out.println("Configuration: " + getCurrentConfig());
        
        List<CompletableFuture<ScrapeResult>> futures = urls.stream()
            .map(url -> CompletableFuture.supplyAsync(() -> scrapeSingleUrl(url, options), executorService))
            .collect(Collectors.toList());
        
        // Wait for all completions and collect results
        List<ScrapeResult> results = futures.stream()
            .map(CompletableFuture::join)
            .collect(Collectors.toList());
        
        System.out.println("Completed scraping " + results.size() + " URLs");
        printScrapingSummary(results);
        
        return results;
    }
    
    private ScrapeResult scrapeSingleUrl(String url, ScrapingOptions options) {
        try {
            System.out.println("Scraping: " + url);
            
            Scraper.ScrapeResponse response = scraper.scrapeWithRetryLogging(url, options.maxRetries);
            
            if (response.error != null) {
                System.err.println("Failed to scrape " + url + ": " + response.error.getMessage());
                return createErrorResult(url, "ERROR: " + response.error.getMessage());
            }
            
            Scraper.Result scraperResult = response.result;
            ScrapeResult result = ScrapeResult.fromResult(scraperResult);
            
            // Apply filtering based on options
            if (!options.extractPeople) {
                result.setPeople(new ArrayList<>());
            }
            if (!options.extractSocial) {
                result.setLinkedinUrls(new ArrayList<>());
                result.setGithubUrls(new ArrayList<>());
            }
            if (!options.extractFacebook) {
                result.setFacebookUrls(new ArrayList<>());
            }
            
            return result;
            
        } catch (Exception e) {
            System.err.println("Exception scraping " + url + ": " + e.getMessage());
            return createErrorResult(url, "EXCEPTION: " + e.getMessage());
        }
    }
    
    private ScrapeResult createErrorResult(String url, String errorMessage) {
        ScrapeResult result = new ScrapeResult(url);
        result.setStatus("FAILED");
        result.setNotes(errorMessage);
        result.setEmails(new ArrayList<>());
        result.setPhones(new ArrayList<>());
        result.setLinkedinUrls(new ArrayList<>());
        result.setGithubUrls(new ArrayList<>());
        result.setFacebookUrls(new ArrayList<>());
        result.setPeople(new ArrayList<>());
        return result;
    }
    
    private void applyScrapingOptions(ScrapingOptions options) {
        if (options != null) {
            this.useDirectConnection = options.useDirectConnection;
            this.extractPeople = options.extractPeople;
            this.extractSocial = options.extractSocial;
            this.extractFacebook = options.extractFacebook;
            this.maxRetries = options.maxRetries;
            
            // Reinitialize scraper with new settings
            if (!useDirectConnection && options.proxyFile != null) {
                this.scraper = new Scraper(
                    options.proxyFile, 
                    true, 
                    Scraper.ProxyRotationStrategy.ROUND_ROBIN,
                    DEFAULT_DELAY_MS,
                    DEFAULT_DOMAIN_COOLDOWN_MS,
                    maxRetries
                );
            } else {
                this.scraper = new Scraper(
                    DEFAULT_DELAY_MS, 
                    DEFAULT_DOMAIN_COOLDOWN_MS, 
                    maxRetries
                );
            }
        }
    }
    
    // Individual setters for backward compatibility
    public void setUseDirectConnection(boolean useDirectConnection) {
        this.useDirectConnection = useDirectConnection;
        initializeScraper();
    }
    
    public void setExtractPeople(boolean extractPeople) {
        this.extractPeople = extractPeople;
    }
    
    public void setExtractSocial(boolean extractSocial) {
        this.extractSocial = extractSocial;
    }
    
    public void setExtractFacebook(boolean extractFacebook) {
        this.extractFacebook = extractFacebook;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        initializeScraper();
    }
    
    public void setProxyFile(String proxyFile) {
        if (proxyFile != null && !proxyFile.trim().isEmpty()) {
            this.scraper = new Scraper(
                proxyFile, 
                true, 
                Scraper.ProxyRotationStrategy.ROUND_ROBIN,
                DEFAULT_DELAY_MS,
                DEFAULT_DOMAIN_COOLDOWN_MS,
                maxRetries
            );
            this.useDirectConnection = false;
        }
    }
    
    // Health check method
    public HealthStatus getHealthStatus() {
        try {
            HealthStatus status = new HealthStatus();
            status.setServiceStatus("OPERATIONAL");
            status.setDatabaseLoaded(nameDatabase.isDatabaseLoaded());
            status.setConnectionType(useDirectConnection ? "DIRECT" : "PROXY");
            status.setActiveFeatures(getActiveFeatures());
            status.setThreadPoolActive(!executorService.isShutdown());
            
            if (nameDatabase.isDatabaseLoaded()) {
                status.setDatabaseStats(String.format(
                    "Names: %d first, %d last", 
                    nameDatabase.getAllFirstNames().size(),
                    nameDatabase.getAllLastNames().size()
                ));
            }
            
            return status;
        } catch (Exception e) {
            HealthStatus status = new HealthStatus();
            status.setServiceStatus("DEGRADED");
            status.setErrorMessage("Health check failed: " + e.getMessage());
            return status;
        }
    }
    
    private String getActiveFeatures() {
        List<String> features = new ArrayList<>();
        if (extractPeople) features.add("People Extraction");
        if (extractSocial) features.add("Social Media");
        if (extractFacebook) features.add("Facebook");
        return String.join(", ", features);
    }
    
    private String getCurrentConfig() {
        return String.format(
            "DirectConnection: %s, People: %s, Social: %s, Facebook: %s, Retries: %d",
            useDirectConnection, extractPeople, extractSocial, extractFacebook, maxRetries
        );
    }
    
    private void printScrapingSummary(List<ScrapeResult> results) {
        if (results == null || results.isEmpty()) return;
        
        long successCount = results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
        long peopleCount = results.stream().mapToLong(r -> r.getPeople() != null ? r.getPeople().size() : 0).sum();
        long emailCount = results.stream().mapToLong(r -> r.getEmails() != null ? r.getEmails().size() : 0).sum();
        long phoneCount = results.stream().mapToLong(r -> r.getPhones() != null ? r.getPhones().size() : 0).sum();
        
        System.out.println("=== SCRAPING SUMMARY ===");
        System.out.println("Total URLs: " + results.size());
        System.out.println("Successful: " + successCount);
        System.out.println("People Found: " + peopleCount);
        System.out.println("Emails Found: " + emailCount);
        System.out.println("Phones Found: " + phoneCount);
        System.out.println("Success Rate: " + String.format("%.1f%%", (double) successCount / results.size() * 100));
    }
    
    // Batch processing for large datasets
    public List<ScrapeResult> scrapeUrlsInBatches(List<String> urls, int batchSize) {
        if (urls == null || urls.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<ScrapeResult> allResults = new ArrayList<>();
        AtomicInteger batchCounter = new AtomicInteger(1);
        
        for (int i = 0; i < urls.size(); i += batchSize) {
            int end = Math.min(i + batchSize, urls.size());
            List<String> batch = urls.subList(i, end);
            
            System.out.println("Processing batch " + batchCounter.get() + " (" + batch.size() + " URLs)");
            
            List<ScrapeResult> batchResults = scrapeUrls(batch);
            allResults.addAll(batchResults);
            
            System.out.println("Completed batch " + batchCounter.getAndIncrement());
            
            // Add delay between batches to be respectful
            if (i + batchSize < urls.size()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return allResults;
    }
    
    // Scraping options inner class
    public static class ScrapingOptions {
        private boolean useDirectConnection = true;
        private boolean extractPeople = true;
        private boolean extractSocial = true;
        private boolean extractFacebook = true;
        private int maxRetries = 3;
        private String proxyFile;
        private boolean autoExport = false;
        private String exportFormat = "standard";
        
        // Getters and setters
        public boolean isUseDirectConnection() { return useDirectConnection; }
        public void setUseDirectConnection(boolean useDirectConnection) { this.useDirectConnection = useDirectConnection; }
        
        public boolean isExtractPeople() { return extractPeople; }
        public void setExtractPeople(boolean extractPeople) { this.extractPeople = extractPeople; }
        
        public boolean isExtractSocial() { return extractSocial; }
        public void setExtractSocial(boolean extractSocial) { this.extractSocial = extractSocial; }
        
        public boolean isExtractFacebook() { return extractFacebook; }
        public void setExtractFacebook(boolean extractFacebook) { this.extractFacebook = extractFacebook; }
        
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        
        public String getProxyFile() { return proxyFile; }
        public void setProxyFile(String proxyFile) { this.proxyFile = proxyFile; }
        
        public boolean isAutoExport() { return autoExport; }
        public void setAutoExport(boolean autoExport) { this.autoExport = autoExport; }
        
        public String getExportFormat() { return exportFormat; }
        public void setExportFormat(String exportFormat) { this.exportFormat = exportFormat; }
    }
    
    // Health status inner class
    public static class HealthStatus {
        private String serviceStatus;
        private boolean databaseLoaded;
        private String connectionType;
        private String activeFeatures;
        private boolean threadPoolActive;
        private String databaseStats;
        private String errorMessage;
        
        // Getters and setters
        public String getServiceStatus() { return serviceStatus; }
        public void setServiceStatus(String serviceStatus) { this.serviceStatus = serviceStatus; }
        
        public boolean isDatabaseLoaded() { return databaseLoaded; }
        public void setDatabaseLoaded(boolean databaseLoaded) { this.databaseLoaded = databaseLoaded; }
        
        public String getConnectionType() { return connectionType; }
        public void setConnectionType(String connectionType) { this.connectionType = connectionType; }
        
        public String getActiveFeatures() { return activeFeatures; }
        public void setActiveFeatures(String activeFeatures) { this.activeFeatures = activeFeatures; }
        
        public boolean isThreadPoolActive() { return threadPoolActive; }
        public void setThreadPoolActive(boolean threadPoolActive) { this.threadPoolActive = threadPoolActive; }
        
        public String getDatabaseStats() { return databaseStats; }
        public void setDatabaseStats(String databaseStats) { this.databaseStats = databaseStats; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
    
     
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            System.out.println("ScraperService executor service shut down");
        }
    }
    
    
    @Override
    protected void finalize() throws Throwable {
        shutdown();
        super.finalize();
    }
}