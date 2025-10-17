package com.example.companyScraper.util;

import com.example.companyScraper.model.ScrapeResult;
import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class CsvExporter {

    private static final String[] STANDARD_HEADERS = {
        "URL", "Status", "Emails", "Phones", "LinkedIns", "GitHubs", "Facebooks", "People", "Notes", "Processed_At"
    };

    private static final String[] DETAILED_HEADERS = {
        "URL", "Domain", "Status", "Email_Count", "Emails", "Phone_Count", "Phones", 
        "LinkedIn_Count", "LinkedIns", "GitHub_Count", "GitHubs", "Facebook_Count", "Facebooks",
        "People_Count", "People", "Total_Contacts", "Has_Contact_Info", "Notes", 
        "Processed_At", "Processing_Time_Ms"
    };

    private static final String BACKUP_DIR = "csv_backups";
    private static final int MAX_CELL_LENGTH = 32767; // Excel cell limit
    private static final String FIELD_SEPARATOR = " | "; // Separator for multiple values

    /**
     * Export results to CSV with standard format
     */
    public static boolean exportToCsv(String filePath, List<ScrapeResult> results) {
        return exportToCsv(filePath, results, ExportFormat.STANDARD, true);
    }

    /**
     * Export results to CSV with detailed format and statistics
     */
    public static boolean exportDetailedCsv(String filePath, List<ScrapeResult> results) {
        return exportToCsv(filePath, results, ExportFormat.DETAILED, true);
    }

    /**
     * Main export method with flexible options
     */
    public static boolean exportToCsv(String filePath, List<ScrapeResult> results, 
                                     ExportFormat format, boolean createBackup) {
        if (results == null || results.isEmpty()) {
            System.err.println("No results to export");
            return false;
        }

        // Ensure directory exists
        Path outputPath = Paths.get(filePath);
        try {
            Files.createDirectories(outputPath.getParent());
        } catch (Exception e) {
            System.err.println("Failed to create output directory: " + e.getMessage());
        }

        // Create backup if file exists
        if (createBackup && Files.exists(outputPath)) {
            createBackup(outputPath);
        }

        // Export with retry mechanism
        int attempts = 0;
        int maxAttempts = 3;
        
        while (attempts < maxAttempts) {
            try {
                boolean success = performExport(filePath, results, format);
                if (success) {
                    System.out.println("Successfully exported " + results.size() + 
                                     " results to: " + filePath);
                    
                    // Generate summary report
                    generateSummaryReport(filePath, results);
                    return true;
                }
            } catch (Exception e) {
                attempts++;
                System.err.println("Export attempt " + attempts + " failed: " + e.getMessage());
                
                if (attempts >= maxAttempts) {
                    System.err.println("Failed to export after " + maxAttempts + " attempts");
                    return false;
                }
                
                // Wait before retry
                try {
                    Thread.sleep(1000 * attempts); // Progressive delay
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        return false;
    }

    private static boolean performExport(String filePath, List<ScrapeResult> results, ExportFormat format) 
            throws IOException {
        
        // Use UTF-8 BOM for better Excel compatibility
        try (FileOutputStream fos = new FileOutputStream(filePath);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(osw, 
                 CSVWriter.DEFAULT_SEPARATOR,
                 CSVWriter.DEFAULT_QUOTE_CHARACTER,
                 CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                 CSVWriter.DEFAULT_LINE_END)) {

            // Write UTF-8 BOM for Excel compatibility
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

            // Write headers
            String[] headers = (format == ExportFormat.DETAILED) ? DETAILED_HEADERS : STANDARD_HEADERS;
            writer.writeNext(headers);

            // Write data rows
            for (ScrapeResult result : results) {
                String[] row = (format == ExportFormat.DETAILED) ? 
                    createDetailedRow(result) : createStandardRow(result);
                writer.writeNext(row);
            }

            writer.flush();
            return true;
        }
    }

    private static String[] createStandardRow(ScrapeResult result) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        return new String[] {
            sanitizeValue(result.getUrl()),
            sanitizeValue(result.getStatus()),
            formatList(result.getEmails()),
            formatList(result.getPhones()),
            formatList(result.getLinkedinUrls()),
            formatList(result.getGithubUrls()),
            formatList(result.getFacebookUrls()),
            formatPeople(result.getPeople()),
            sanitizeValue(result.getNotes()),
            timestamp
        };
    }

    private static String[] createDetailedRow(ScrapeResult result) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String domain = extractDomain(result.getUrl());
        
        int emailCount = result.getEmails() != null ? result.getEmails().size() : 0;
        int phoneCount = result.getPhones() != null ? result.getPhones().size() : 0;
        int linkedinCount = result.getLinkedinUrls() != null ? result.getLinkedinUrls().size() : 0;
        int githubCount = result.getGithubUrls() != null ? result.getGithubUrls().size() : 0;
        int facebookCount = result.getFacebookUrls() != null ? result.getFacebookUrls().size() : 0;
        int peopleCount = result.getPeople() != null ? result.getPeople().size() : 0;
        int totalContacts = emailCount + phoneCount + linkedinCount + githubCount + facebookCount + peopleCount;
        
        return new String[] {
            sanitizeValue(result.getUrl()),
            sanitizeValue(domain),
            sanitizeValue(result.getStatus()),
            String.valueOf(emailCount),
            formatListDetailed(result.getEmails()),
            String.valueOf(phoneCount),
            formatListDetailed(result.getPhones()),
            String.valueOf(linkedinCount),
            formatListDetailed(result.getLinkedinUrls()),
            String.valueOf(githubCount),
            formatListDetailed(result.getGithubUrls()),
            String.valueOf(facebookCount),
            formatListDetailed(result.getFacebookUrls()),
            String.valueOf(peopleCount),
            formatPeopleDetailed(result.getPeople()),
            String.valueOf(totalContacts),
            totalContacts > 0 ? "YES" : "NO",
            sanitizeValue(result.getNotes()),
            timestamp,
            "" // Processing time - would need to be tracked separately
        };
    }

    private static String formatList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        
        String formatted = list.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(FIELD_SEPARATOR));
        
        return truncateIfNeeded(formatted);
    }

    private static String formatListDetailed(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        
        // For detailed format, show each item on a separate line within the cell
        String formatted = list.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining("\n"));
        
        return truncateIfNeeded(formatted);
    }

    private static String formatPeople(List<ScrapeResult.Person> people) {
        if (people == null || people.isEmpty()) {
            return "";
        }
        
        return people.stream()
            .filter(Objects::nonNull)
            .map(person -> {
                String name = (person.getFirstName() != null ? person.getFirstName() : "") + 
                             " " + (person.getLastName() != null ? person.getLastName() : "");
                String role = person.getRole() != null ? person.getRole() : "";
                return name.trim() + (role.isEmpty() ? "" : " (" + role + ")");
            })
            .filter(s -> !s.trim().isEmpty())
            .collect(Collectors.joining(FIELD_SEPARATOR));
    }

    private static String formatPeopleDetailed(List<ScrapeResult.Person> people) {
        if (people == null || people.isEmpty()) {
            return "";
        }
        
        return people.stream()
            .filter(Objects::nonNull)
            .map(person -> {
                String name = (person.getFirstName() != null ? person.getFirstName() : "") + 
                             " " + (person.getLastName() != null ? person.getLastName() : "");
                String role = person.getRole() != null ? person.getRole() : "";
                String email = person.getEmail() != null ? "Email: " + person.getEmail() : "";
                String phone = person.getPhone() != null ? "Phone: " + person.getPhone() : "";
                
                List<String> parts = new ArrayList<>();
                parts.add("Name: " + name.trim());
                if (!role.isEmpty()) parts.add("Role: " + role);
                if (!email.isEmpty()) parts.add(email);
                if (!phone.isEmpty()) parts.add(phone);
                
                return String.join(" | ", parts);
            })
            .collect(Collectors.joining("\n---\n"));
    }

    private static String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        
        // Remove or replace problematic characters
        String sanitized = value
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
            .trim();
        
        return truncateIfNeeded(sanitized);
    }

    private static String truncateIfNeeded(String value) {
        if (value == null) return "";
        
        if (value.length() > MAX_CELL_LENGTH) {
            return value.substring(0, MAX_CELL_LENGTH - 3) + "...";
        }
        
        return value;
    }

    private static String extractDomain(String url) {
        if (url == null || url.isEmpty()) return "";
        
        try {
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
            return new java.net.URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static void createBackup(Path originalFile) {
        try {
            // Create backup directory
            Path backupDir = Paths.get(BACKUP_DIR);
            Files.createDirectories(backupDir);
            
            // Create backup filename with timestamp
            String originalName = originalFile.getFileName().toString();
            String nameWithoutExt = originalName.substring(0, originalName.lastIndexOf('.'));
            String extension = originalName.substring(originalName.lastIndexOf('.'));
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            
            String backupName = nameWithoutExt + "_backup_" + timestamp + extension;
            Path backupPath = backupDir.resolve(backupName);
            
            Files.copy(originalFile, backupPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Backup created: " + backupPath);
            
            // Clean old backups (keep only last 10)
            cleanOldBackups(backupDir, nameWithoutExt);
            
        } catch (Exception e) {
            System.err.println("Failed to create backup: " + e.getMessage());
        }
    }

    private static void cleanOldBackups(Path backupDir, String prefix) throws IOException {
        List<Path> backupFiles = Files.list(backupDir)
            .filter(path -> path.getFileName().toString().startsWith(prefix + "_backup_"))
            .sorted(Comparator.comparing(path -> {
                try {
                    return Files.getLastModifiedTime(path);
                } catch (IOException e) {
                    return null;
                }
            }))
            .collect(Collectors.toList());
        
        // Keep only the 10 most recent backups
        if (backupFiles.size() > 10) {
            for (int i = 0; i < backupFiles.size() - 10; i++) {
                try {
                    Files.delete(backupFiles.get(i));
                    System.out.println("Deleted old backup: " + backupFiles.get(i).getFileName());
                } catch (IOException e) {
                    System.err.println("Failed to delete old backup: " + e.getMessage());
                }
            }
        }
    }

    private static void generateSummaryReport(String csvFilePath, List<ScrapeResult> results) {
        try {
            String summaryPath = csvFilePath.replace(".csv", "_summary.txt");
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(summaryPath, StandardCharsets.UTF_8))) {
                writer.println("SCRAPING SUMMARY REPORT");
                writer.println("=".repeat(50));
                writer.println("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                writer.println();
                
                // Basic statistics
                int total = results.size();
                long successful = results.stream().mapToLong(r -> "SUCCESS".equals(r.getStatus()) ? 1 : 0).sum();
                long failed = total - successful;
                
                writer.println("BASIC STATISTICS:");
                writer.println("Total URLs processed: " + total);
                writer.println("Successful: " + successful + " (" + String.format("%.1f", (double)successful/total*100) + "%)");
                writer.println("Failed: " + failed + " (" + String.format("%.1f", (double)failed/total*100) + "%)");
                writer.println();
                
                // Contact info statistics
                long emailsFound = results.stream().mapToLong(r -> r.getEmails() != null ? r.getEmails().size() : 0).sum();
                long phonesFound = results.stream().mapToLong(r -> r.getPhones() != null ? r.getPhones().size() : 0).sum();
                long linkedinsFound = results.stream().mapToLong(r -> r.getLinkedinUrls() != null ? r.getLinkedinUrls().size() : 0).sum();
                long githubsFound = results.stream().mapToLong(r -> r.getGithubUrls() != null ? r.getGithubUrls().size() : 0).sum();
                long facebooksFound = results.stream().mapToLong(r -> r.getFacebookUrls() != null ? r.getFacebookUrls().size() : 0).sum();
                long peopleFound = results.stream().mapToLong(r -> r.getPeople() != null ? r.getPeople().size() : 0).sum();
                
                writer.println("CONTACT INFORMATION FOUND:");
                writer.println("Emails: " + emailsFound);
                writer.println("Phone numbers: " + phonesFound);
                writer.println("LinkedIn profiles: " + linkedinsFound);
                writer.println("GitHub profiles: " + githubsFound);
                writer.println("Facebook profiles: " + facebooksFound);
                writer.println("People identified: " + peopleFound);
                writer.println("Total contacts: " + (emailsFound + phonesFound + linkedinsFound + githubsFound + facebooksFound + peopleFound));
                writer.println();
                
                // URLs with most contact info
                writer.println("TOP URLs BY CONTACT COUNT:");
                results.stream()
                    .filter(r -> "SUCCESS".equals(r.getStatus()))
                    .sorted((a, b) -> {
                        int aCount = (a.getEmails() != null ? a.getEmails().size() : 0) +
                                    (a.getPhones() != null ? a.getPhones().size() : 0) +
                                    (a.getLinkedinUrls() != null ? a.getLinkedinUrls().size() : 0) +
                                    (a.getGithubUrls() != null ? a.getGithubUrls().size() : 0) +
                                    (a.getFacebookUrls() != null ? a.getFacebookUrls().size() : 0) +
                                    (a.getPeople() != null ? a.getPeople().size() : 0);
                        int bCount = (b.getEmails() != null ? b.getEmails().size() : 0) +
                                    (b.getPhones() != null ? b.getPhones().size() : 0) +
                                    (b.getLinkedinUrls() != null ? b.getLinkedinUrls().size() : 0) +
                                    (b.getGithubUrls() != null ? b.getGithubUrls().size() : 0) +
                                    (b.getFacebookUrls() != null ? b.getFacebookUrls().size() : 0) +
                                    (b.getPeople() != null ? b.getPeople().size() : 0);
                        return Integer.compare(bCount, aCount);
                    })
                    .limit(10)
                    .forEach(r -> {
                        int contactCount = (r.getEmails() != null ? r.getEmails().size() : 0) +
                                         (r.getPhones() != null ? r.getPhones().size() : 0) +
                                         (r.getLinkedinUrls() != null ? r.getLinkedinUrls().size() : 0) +
                                         (r.getGithubUrls() != null ? r.getGithubUrls().size() : 0) +
                                         (r.getFacebookUrls() != null ? r.getFacebookUrls().size() : 0) +
                                         (r.getPeople() != null ? r.getPeople().size() : 0);
                        if (contactCount > 0) {
                            writer.println("  " + contactCount + " contacts - " + r.getUrl());
                        }
                    });
                
                // People statistics
                writer.println("\nPEOPLE IDENTIFIED:");
                Map<String, Long> roleStats = results.stream()
                    .filter(r -> r.getPeople() != null)
                    .flatMap(r -> r.getPeople().stream())
                    .filter(p -> p.getRole() != null && !p.getRole().isEmpty())
                    .collect(Collectors.groupingBy(p -> p.getRole().toUpperCase(), Collectors.counting()));
                
                roleStats.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(15)
                    .forEach(entry -> writer.println("  " + entry.getKey() + ": " + entry.getValue()));
                
                // Domain statistics
                writer.println("\nDOMAIN STATISTICS:");
                Map<String, Long> domainStats = results.stream()
                    .collect(Collectors.groupingBy(r -> extractDomain(r.getUrl()), Collectors.counting()));
                
                domainStats.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(20)
                    .forEach(entry -> writer.println("  " + entry.getKey() + ": " + entry.getValue() + " URLs"));
                
                writer.println("\n" + "=".repeat(50));
                writer.println("CSV file: " + csvFilePath);
            }
            
            System.out.println("Summary report generated: " + summaryPath);
            
        } catch (Exception e) {
            System.err.println("Failed to generate summary report: " + e.getMessage());
        }
    }

    /**
     * Export results in multiple formats simultaneously
     */
    public static void exportMultipleFormats(String baseFilename, List<ScrapeResult> results) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String basePath = baseFilename.replace(".csv", "_" + timestamp);
        
        // Standard format
        exportToCsv(basePath + ".csv", results, ExportFormat.STANDARD, false);
        
        // Detailed format
        exportToCsv(basePath + "_detailed.csv", results, ExportFormat.DETAILED, false);
        
        // Contact-only format (only URLs with contact info)
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
            .collect(Collectors.toList());
        
        if (!contactResults.isEmpty()) {
            exportToCsv(basePath + "_contacts_only.csv", contactResults, ExportFormat.DETAILED, false);
            System.out.println("Exported " + contactResults.size() + " URLs with contact information");
        }
        
        // People-only format
        List<ScrapeResult> peopleResults = results.stream()
            .filter(r -> r.getPeople() != null && !r.getPeople().isEmpty())
            .collect(Collectors.toList());
        
        if (!peopleResults.isEmpty()) {
            exportToCsv(basePath + "_people_only.csv", peopleResults, ExportFormat.DETAILED, false);
            System.out.println("Exported " + peopleResults.size() + " URLs with people information");
        }
    }

    public enum ExportFormat {
        STANDARD, DETAILED
    }
}