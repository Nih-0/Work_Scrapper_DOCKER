package com.example.companyScraper.util;

import org.apache.commons.validator.routines.EmailValidator;
import java.util.*;
import java.util.regex.Pattern;

public class EmailExtractor {
    
    private static final EmailValidator emailValidator = EmailValidator.getInstance();
    
    // Common disposable email domains
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
        "tempmail.com", "guerrillamail.com", "mailinator.com", "10minutemail.com",
        "throwaway.com", "fakeinbox.com", "yopmail.com", "trashmail.com",
        "temp-mail.org", "getairmail.com", "dispostable.com"
    );
    
    // Common false positive patterns
    private static final Set<String> FALSE_POSITIVE_PATTERNS = Set.of(
        "email@example.com", "info@example.com", "test@example.com",
        "user@example.com", "admin@example.com"
    );

    public static Set<String> extractEmails(String html) {
        Set<String> emails = new HashSet<>();
        
        // Multiple regex patterns for better coverage
        String[] emailPatterns = {
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\.[a-zA-Z]{2,}", // For .co.uk etc.
            "mailto:([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"
        };
        
        for (String patternStr : emailPatterns) {
            Pattern pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
            var matcher = pattern.matcher(html);
            while (matcher.find()) {
                String email = matcher.group().toLowerCase();
                if (email.startsWith("mailto:")) {
                    email = email.substring(7); // Remove mailto: prefix
                }
                
                if (isValidEmail(email)) {
                    emails.add(email);
                }
            }
        }
        
        // Extract from mailto links specifically
        extractFromMailtoLinks(html, emails);
        
        return emails;
    }
    
    private static void extractFromMailtoLinks(String html, Set<String> emails) {
        Pattern mailtoPattern = Pattern.compile("mailto:([^\"'\\s?]+)", Pattern.CASE_INSENSITIVE);
        var matcher = mailtoPattern.matcher(html);
        while (matcher.find()) {
            String email = matcher.group(1).toLowerCase();
            if (isValidEmail(email)) {
                emails.add(email);
            }
        }
    }
    
    private static boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) return false;
        
        // Basic validation
        if (!emailValidator.isValid(email)) return false;
        
        // Check for disposable domains
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase();
        if (DISPOSABLE_DOMAINS.contains(domain)) return false;
        
        // Check for false positives
        if (FALSE_POSITIVE_PATTERNS.contains(email.toLowerCase())) return false;
        
        // Check for common patterns
        if (email.endsWith("@example.com") || 
            email.endsWith("@domain.com") || 
            email.endsWith("@test.com")) {
            return false;
        }
        
        return true;
    }
}