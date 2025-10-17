package com.example.companyScraper.controller;

import com.example.companyScraper.model.ScrapeResult;
import com.example.companyScraper.service.ScraperService;
import com.example.companyScraper.util.CsvExporter;
import com.example.companyScraper.util.InputReader;
import com.opencsv.CSVWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/urls")
public class FileUploadController {

    private final ScraperService scraperService;
    private final InputReader inputReader;

    public FileUploadController(ScraperService scraperService, InputReader inputReader) {
        this.scraperService = scraperService;
        this.inputReader = inputReader;
    }

    @PostMapping("/upload")
    public ResponseEntity<byte[]> uploadFile(@RequestParam("file") MultipartFile file) throws Exception {
        return uploadFileWithOptions(file, "standard", false, null);
    }

    @PostMapping("/upload/{format}")
    public ResponseEntity<byte[]> uploadFileWithFormat(
            @RequestParam("file") MultipartFile file,
            @PathVariable String format) throws Exception {
        return uploadFileWithOptions(file, format, false, null);
    }

    @PostMapping("/upload/{format}/detailed")
    public ResponseEntity<byte[]> uploadFileDetailed(
            @RequestParam("file") MultipartFile file,
            @PathVariable String format,
            @RequestParam(defaultValue = "false") boolean includeSummary) throws Exception {
        return uploadFileWithOptions(file, format, includeSummary, null);
    }

    // FIXED: Return CSV file directly, not wrapped in JSON
    @PostMapping("/upload/advanced")
    public ResponseEntity<byte[]> uploadFileAdvanced(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "standard") String format,
            @RequestParam(defaultValue = "false") boolean includeSummary,
            @RequestParam(defaultValue = "true") boolean extractPeople,
            @RequestParam(defaultValue = "true") boolean extractSocial,
            @RequestParam(defaultValue = "true") boolean extractFacebook,
            @RequestParam(defaultValue = "3") int maxRetries,
            @RequestParam(defaultValue = "true") boolean useDirectConnection) throws Exception {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded".getBytes());
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().body("No URLs found".getBytes());
        }

        // Set connection preferences - DIRECT CONNECTION CHECKBOX
        scraperService.setUseDirectConnection(useDirectConnection);

        // Create scraping options
        ScraperService.ScrapingOptions options = new ScraperService.ScrapingOptions();
        options.setExtractPeople(extractPeople);
        options.setExtractSocial(extractSocial);
        options.setExtractFacebook(extractFacebook);
        options.setMaxRetries(maxRetries);
        options.setUseDirectConnection(useDirectConnection);
        options.setAutoExport(false);
        options.setExportFormat(format);

        List<ScrapeResult> results = scraperService.scrapeUrls(urls, options);

        Path tempOutput = Files.createTempFile("scraped_advanced_", ".csv");
        
        try {
            exportStandardCsv(tempOutput, results);
            byte[] csvBytes = Files.readAllBytes(tempOutput);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=scraped_advanced_results.csv");
            headers.setContentType(MediaType.parseMediaType("text/csv"));

            return ResponseEntity.ok().headers(headers).body(csvBytes);

        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }

    // NEW: Separate endpoint for getting advanced scraping statistics (JSON response)
    @PostMapping("/upload/advanced/stats")
    public ResponseEntity<AdvancedScrapingStats> uploadFileAdvancedStats(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean extractPeople,
            @RequestParam(defaultValue = "true") boolean extractSocial,
            @RequestParam(defaultValue = "true") boolean extractFacebook,
            @RequestParam(defaultValue = "3") int maxRetries,
            @RequestParam(defaultValue = "true") boolean useDirectConnection) throws Exception {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new AdvancedScrapingStats("No file uploaded", null));
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().body(new AdvancedScrapingStats("No URLs found", null));
        }

        // Set connection preferences
        scraperService.setUseDirectConnection(useDirectConnection);

        // Create scraping options
        ScraperService.ScrapingOptions options = new ScraperService.ScrapingOptions();
        options.setExtractPeople(extractPeople);
        options.setExtractSocial(extractSocial);
        options.setExtractFacebook(extractFacebook);
        options.setMaxRetries(maxRetries);
        options.setUseDirectConnection(useDirectConnection);
        options.setAutoExport(false);

        List<ScrapeResult> results = scraperService.scrapeUrls(urls, options);

        ScrapingStats stats = calculateStats(results);
        String connectionType = useDirectConnection ? "DIRECT" : "PROXY";
        String message = String.format("Scraping completed (%s connection): %d URLs processed, %d successful, %d with contact info, %d people identified",
                connectionType, results.size(), stats.getSuccessfulCount(), stats.getWithContactInfoCount(), stats.getPeopleCount());

        return ResponseEntity.ok(new AdvancedScrapingStats(message, stats));
    }

    
    @GetMapping("/health")
    public ResponseEntity<Object> getHealthStatus() {
        try {
            var healthStatus = scraperService.getHealthStatus();
            return ResponseEntity.ok(healthStatus);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Health check failed: " + e.getMessage());
        }
    }

    
    @PostMapping("/upload/legacy")
    public ResponseEntity<byte[]> uploadFileLegacy(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean extractPeople,
            @RequestParam(defaultValue = "true") boolean extractSocial,
            @RequestParam(defaultValue = "3") int maxRetries,
            @RequestParam(defaultValue = "true") boolean useDirectConnection) throws Exception {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded".getBytes());
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().body("No URLs found".getBytes());
        }

        
        scraperService.setExtractPeople(extractPeople);
        scraperService.setExtractSocial(extractSocial);
        scraperService.setMaxRetries(maxRetries);
        scraperService.setUseDirectConnection(useDirectConnection);

        
        List<ScrapeResult> results = scraperService.scrapeUrls(urls);

        Path tempOutput = Files.createTempFile("scraped_legacy_", ".csv");
        try {
            exportStandardCsv(tempOutput, results);
            byte[] csvBytes = Files.readAllBytes(tempOutput);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=scraped_legacy_results.csv");
            headers.setContentType(MediaType.parseMediaType("text/csv"));

            return ResponseEntity.ok().headers(headers).body(csvBytes);

        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }

    private ResponseEntity<byte[]> uploadFileWithOptions(MultipartFile file, String format, 
                                                        boolean includeSummary, ScraperService.ScrapingOptions options) 
            throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded".getBytes());
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().body("No URLs found".getBytes());
        }

        // Validate format
        if (!isValidFormat(format)) {
            return ResponseEntity.badRequest().body("Invalid format. Use: standard, detailed, people, contacts".getBytes());
        }

        List<ScrapeResult> results;
        if (options != null) {
            results = scraperService.scrapeUrls(urls, options);
        } else {
            results = scraperService.scrapeUrls(urls);
        }

        Path tempOutput = Files.createTempFile("scraped_results_", ".csv");
        
        try {
            // Export based on format
            switch (format.toLowerCase()) {
                case "detailed":
                    exportDetailedCsv(tempOutput, results);
                    break;
                case "people":
                    exportPeopleOnlyCsv(tempOutput, results);
                    break;
                case "contacts":
                    exportContactsOnlyCsv(tempOutput, results);
                    break;
                case "standard":
                default:
                    exportStandardCsv(tempOutput, results);
                    break;
            }

            byte[] csvBytes = Files.readAllBytes(tempOutput);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + getFilename(format));
            headers.setContentType(MediaType.parseMediaType("text/csv"));

            return ResponseEntity.ok().headers(headers).body(csvBytes);

        } finally {
            // Clean up temp file
            Files.deleteIfExists(tempOutput);
        }
    }

    @PostMapping("/upload/multiple")
    public ResponseEntity<String> uploadFileMultipleFormats(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded");
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().body("No URLs found");
        }

        List<ScrapeResult> results = scraperService.scrapeUrls(urls);

        // Generate base filename with timestamp
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String baseFilename = "scraped_results_" + timestamp;

        // Export in multiple formats
        CsvExporter.exportMultipleFormats(baseFilename, results);

        return ResponseEntity.ok("Files exported successfully: " + baseFilename + "*.csv");
    }

    @GetMapping("/stats")
    public ResponseEntity<ScrapingStats> getScrapingStats(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<String> urls = inputReader.readUrls(file);
        if (urls.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<ScrapeResult> results = scraperService.scrapeUrls(urls);
        ScrapingStats stats = calculateStats(results);

        return ResponseEntity.ok(stats);
    }

    // Helper method to export standard CSV
    private void exportStandardCsv(Path tempOutput, List<ScrapeResult> results) throws IOException {
        try (CSVWriter writer = new CSVWriter(new FileWriter(tempOutput.toFile()))) {
            String[] header = {"URL", "Status", "Emails", "Phones", "LinkedIns", "GitHubs", "Facebooks", "People", "Notes"};
            writer.writeNext(header);

            for (ScrapeResult r : results) {
                String[] row = {
                    r.getUrl(),
                    r.getStatus(),
                    String.join(";", r.getEmails()),
                    String.join(";", r.getPhones()),
                    String.join(";", r.getLinkedinUrls()),
                    String.join(";", r.getGithubUrls()),
                    String.join(";", r.getFacebookUrls()),
                    formatPeopleForCsv(r.getPeople()),
                    r.getNotes() != null ? r.getNotes() : ""
                };
                writer.writeNext(row);
            }
        }
    }

    private void exportDetailedCsv(Path tempOutput, List<ScrapeResult> results) throws IOException {
        CsvExporter.exportDetailedCsv(tempOutput.toString(), results);
    }

    private void exportPeopleOnlyCsv(Path tempOutput, List<ScrapeResult> results) throws IOException {
        List<ScrapeResult> peopleResults = results.stream()
            .filter(r -> r.getPeople() != null && !r.getPeople().isEmpty())
            .toList();
        
        if (!peopleResults.isEmpty()) {
            CsvExporter.exportToCsv(tempOutput.toString(), peopleResults, CsvExporter.ExportFormat.DETAILED, false);
        } else {
            exportStandardCsv(tempOutput, results); 
        }
    }

    private void exportContactsOnlyCsv(Path tempOutput, List<ScrapeResult> results) throws IOException {
        List<ScrapeResult> contactResults = results.stream()
            .filter(r -> {
                int contacts = (r.getEmails() != null ? r.getEmails().size() : 0) +
                             (r.getPhones() != null ? r.getPhones().size() : 0) +
                             (r.getLinkedinUrls() != null ? r.getLinkedinUrls().size() : 0) +
                             (r.getGithubUrls() != null ? r.getGithubUrls().size() : 0) +
                             (r.getFacebookUrls() != null ? r.getFacebookUrls().size() : 0) +
                             (r.getPeople() != null ? r.getPeople().size() : 0);
                return contacts > 0;
            })
            .toList();
        
        if (!contactResults.isEmpty()) {
            CsvExporter.exportToCsv(tempOutput.toString(), contactResults, CsvExporter.ExportFormat.DETAILED, false);
        } else {
            exportStandardCsv(tempOutput, results); 
        }
    }

    private String formatPeopleForCsv(List<ScrapeResult.Person> people) {
        if (people == null || people.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ScrapeResult.Person person : people) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            String name = (person.getFirstName() != null ? person.getFirstName() : "") + 
                         " " + (person.getLastName() != null ? person.getLastName() : "");
            String role = person.getRole() != null ? person.getRole() : "";
            sb.append(name.trim());
            if (!role.isEmpty()) {
                sb.append(" (").append(role).append(")");
            }
        }
        return sb.toString();
    }

    private String getFilename(String format) {
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        switch (format.toLowerCase()) {
            case "detailed":
                return "scraped_detailed_" + timestamp + ".csv";
            case "people":
                return "scraped_people_" + timestamp + ".csv";
            case "contacts":
                return "scraped_contacts_" + timestamp + ".csv";
            case "standard":
            default:
                return "scraped_results_" + timestamp + ".csv";
        }
    }

    private boolean isValidFormat(String format) {
        return List.of("standard", "detailed", "people", "contacts").contains(format.toLowerCase());
    }

    private byte[] generateSummaryBytes(List<ScrapeResult> results) throws IOException {
        ScrapingStats stats = calculateStats(results);
        
        String summary = String.format(
            "SCRAPING SUMMARY REPORT\n" +
            "=======================\n" +
            "Generated: %s\n\n" +
            "BASIC STATISTICS:\n" +
            "Total URLs processed: %d\n" +
            "Successful: %d (%.1f%%)\n" +
            "Failed: %d (%.1f%%)\n\n" +
            "CONTACT INFORMATION FOUND:\n" +
            "Emails: %d\n" +
            "Phone numbers: %d\n" +
            "LinkedIn profiles: %d\n" +
            "GitHub profiles: %d\n" +
            "Facebook profiles: %d\n" +
            "People identified: %d\n" +
            "Total contacts: %d\n\n" +
            "TOP ROLES IDENTIFIED:\n%s",
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            stats.getTotalCount(),
            stats.getSuccessfulCount(),
            stats.getSuccessRate(),
            stats.getFailedCount(),
            stats.getFailureRate(),
            stats.getEmailCount(),
            stats.getPhoneCount(),
            stats.getLinkedinCount(),
            stats.getGithubCount(),
            stats.getFacebookCount(),
            stats.getPeopleCount(),
            stats.getTotalContacts(),
            formatTopRoles(stats.getTopRoles())
        );
        
        return summary.getBytes();
    }

    private String formatTopRoles(List<String> topRoles) {
        if (topRoles.isEmpty()) {
            return "  No roles identified";
        }
        
        StringBuilder sb = new StringBuilder();
        for (String role : topRoles) {
            sb.append("  ").append(role).append("\n");
        }
        return sb.toString();
    }

    private ScrapingStats calculateStats(List<ScrapeResult> results) {
        ScrapingStats stats = new ScrapingStats();
        
        stats.setTotalCount(results.size());
        stats.setSuccessfulCount((int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count());
        stats.setFailedCount(stats.getTotalCount() - stats.getSuccessfulCount());
        
        // Contact counts
        stats.setEmailCount(results.stream().mapToLong(r -> r.getEmails() != null ? r.getEmails().size() : 0).sum());
        stats.setPhoneCount(results.stream().mapToLong(r -> r.getPhones() != null ? r.getPhones().size() : 0).sum());
        stats.setLinkedinCount(results.stream().mapToLong(r -> r.getLinkedinUrls() != null ? r.getLinkedinUrls().size() : 0).sum());
        stats.setGithubCount(results.stream().mapToLong(r -> r.getGithubUrls() != null ? r.getGithubUrls().size() : 0).sum());
        stats.setFacebookCount(results.stream().mapToLong(r -> r.getFacebookUrls() != null ? r.getFacebookUrls().size() : 0).sum());
        stats.setPeopleCount(results.stream().mapToLong(r -> r.getPeople() != null ? r.getPeople().size() : 0).sum());
        stats.setTotalContacts(stats.getEmailCount() + stats.getPhoneCount() + stats.getLinkedinCount() + 
                             stats.getGithubCount() + stats.getFacebookCount() + stats.getPeopleCount());
        
        stats.setWithContactInfoCount((int) results.stream()
            .filter(r -> {
                int contacts = (r.getEmails() != null ? r.getEmails().size() : 0) +
                             (r.getPhones() != null ? r.getPhones().size() : 0) +
                             (r.getLinkedinUrls() != null ? r.getLinkedinUrls().size() : 0) +
                             (r.getGithubUrls() != null ? r.getGithubUrls().size() : 0) +
                             (r.getFacebookUrls() != null ? r.getFacebookUrls().size() : 0) +
                             (r.getPeople() != null ? r.getPeople().size() : 0);
                return contacts > 0;
            })
            .count());
        
       
        Map<String, Long> roleCounts = results.stream()
            .filter(r -> r.getPeople() != null)
            .flatMap(r -> r.getPeople().stream())
            .filter(p -> p.getRole() != null && !p.getRole().isEmpty())
            .collect(java.util.stream.Collectors.groupingBy(
                p -> p.getRole().toUpperCase(),
                java.util.stream.Collectors.counting()
            ));
        
        List<String> topRoles = roleCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .toList();
        
        stats.setTopRoles(topRoles);
        
        return stats;
    }

    
    public static class ScrapingStats {
        private int totalCount;
        private int successfulCount;
        private int failedCount;
        private int withContactInfoCount;
        private long emailCount;
        private long phoneCount;
        private long linkedinCount;
        private long githubCount;
        private long facebookCount;
        private long peopleCount;
        private long totalContacts;
        private List<String> topRoles;

        public ScrapingStats() {
            this.topRoles = new ArrayList<>();
        }

        
        public int getTotalCount() { return totalCount; }
        public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
        
        public int getSuccessfulCount() { return successfulCount; }
        public void setSuccessfulCount(int successfulCount) { this.successfulCount = successfulCount; }
        
        public int getFailedCount() { return failedCount; }
        public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
        
        public int getWithContactInfoCount() { return withContactInfoCount; }
        public void setWithContactInfoCount(int withContactInfoCount) { this.withContactInfoCount = withContactInfoCount; }
        
        public long getEmailCount() { return emailCount; }
        public void setEmailCount(long emailCount) { this.emailCount = emailCount; }
        
        public long getPhoneCount() { return phoneCount; }
        public void setPhoneCount(long phoneCount) { this.phoneCount = phoneCount; }
        
        public long getLinkedinCount() { return linkedinCount; }
        public void setLinkedinCount(long linkedinCount) { this.linkedinCount = linkedinCount; }
        
        public long getGithubCount() { return githubCount; }
        public void setGithubCount(long githubCount) { this.githubCount = githubCount; }
        
        public long getFacebookCount() { return facebookCount; }
        public void setFacebookCount(long facebookCount) { this.facebookCount = facebookCount; }
        
        public long getPeopleCount() { return peopleCount; }
        public void setPeopleCount(long peopleCount) { this.peopleCount = peopleCount; }
        
        public long getTotalContacts() { return totalContacts; }
        public void setTotalContacts(long totalContacts) { this.totalContacts = totalContacts; }
        
        public List<String> getTopRoles() { return topRoles; }
        public void setTopRoles(List<String> topRoles) { this.topRoles = topRoles; }
        
        public double getSuccessRate() {
            return totalCount > 0 ? (double) successfulCount / totalCount * 100 : 0;
        }
        
        public double getFailureRate() {
            return totalCount > 0 ? (double) failedCount / totalCount * 100 : 0;
        }
        
        public double getContactInfoRate() {
            return totalCount > 0 ? (double) withContactInfoCount / totalCount * 100 : 0;
        }
    }

    
    public static class AdvancedScrapingStats {
        private String message;
        private ScrapingStats stats;

        public AdvancedScrapingStats() {}

        public AdvancedScrapingStats(String message, ScrapingStats stats) {
            this.message = message;
            this.stats = stats;
        }

       
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public ScrapingStats getStats() { return stats; }
        public void setStats(ScrapingStats stats) { this.stats = stats; }
    }
}