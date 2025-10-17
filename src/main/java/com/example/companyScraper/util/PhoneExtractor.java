package com.example.companyScraper.util;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.apache.commons.validator.routines.EmailValidator;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PhoneExtractor {
    
    private static final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
    private static final Set<String> COUNTRY_CODES = Set.of("US", "GB", "CA", "AU", "IN", "DE", "FR", "BR", "CN");
    
    // Enhanced phone regex patterns
    private static final List<Pattern> PHONE_PATTERNS = Arrays.asList(
        // International format
        Pattern.compile("\\+?\\d{1,3}[\\s\\-]?\\d{1,4}[\\s\\-]?\\d{1,4}[\\s\\-]?\\d{1,4}"),
        // US/CA format
        Pattern.compile("\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{4}"),
        // With extensions
        Pattern.compile("\\d{3}[\\s\\-.]?\\d{3}[\\s\\-.]?\\d{4}\\s*(?:x|ext|extension)\\.?\\s*\\d{2,5}", Pattern.CASE_INSENSITIVE),
        // Toll-free numbers
        Pattern.compile("\\b(?:800|888|877|866|855|844|833|822)[\\s\\-.]?\\d{3}[\\s\\-.]?\\d{4}\\b")
    );
    
    // Common false positives to exclude
    private static final Set<String> FALSE_POSITIVES = Set.of(
        "1234567890", "0000000000", "1111111111", "9999999999",
        "0123456789", "1000000000", "2000000000"
    );

    public static Set<String> extractPhones(String text) {
        return extractValidNormalizedPhones(text, "US");
    }

    public static Set<String> extractValidNormalizedPhones(String text, String defaultRegion) {
        if (text == null || text.isEmpty()) return new HashSet<>();
        
        Set<String> allPotentialPhones = new HashSet<>();
        
        // Extract using multiple patterns
        for (Pattern pattern : PHONE_PATTERNS) {
            var matcher = pattern.matcher(text);
            while (matcher.find()) {
                String phone = cleanPhoneString(matcher.group());
                if (isValidPhoneLength(phone) && !isFalsePositive(phone)) {
                    allPotentialPhones.add(phone);
                }
            }
        }
        
        // Extract from tel: links and other attributes
        allPotentialPhones.addAll(extractFromHtmlAttributes(text));
        
        // Validate and normalize using libphonenumber
        return allPotentialPhones.stream()
            .map(phone -> normalizeWithLibPhoneNumber(phone, defaultRegion))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }
    
    private static Set<String> extractFromHtmlAttributes(String html) {
        Set<String> phones = new HashSet<>();
        
        // Extract from tel: links
        var telPattern = Pattern.compile("tel:([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE);
        var telMatcher = telPattern.matcher(html);
        while (telMatcher.find()) {
            String phone = cleanPhoneString(telMatcher.group(1));
            if (isValidPhoneLength(phone)) {
                phones.add(phone);
            }
        }
        
        // Extract from data-phone attributes
        var dataPattern = Pattern.compile("data-phone=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        var dataMatcher = dataPattern.matcher(html);
        while (dataMatcher.find()) {
            String phone = cleanPhoneString(dataMatcher.group(1));
            if (isValidPhoneLength(phone)) {
                phones.add(phone);
            }
        }
        
        return phones;
    }
    
    private static String normalizeWithLibPhoneNumber(String phone, String defaultRegion) {
        try {
            Phonenumber.PhoneNumber number = phoneUtil.parse(phone, defaultRegion);
            if (phoneUtil.isValidNumber(number)) {
                return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
            }
            
            // Try with other common country codes if default fails
            for (String countryCode : COUNTRY_CODES) {
                if (!countryCode.equals(defaultRegion)) {
                    number = phoneUtil.parse(phone, countryCode);
                    if (phoneUtil.isValidNumber(number)) {
                        return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to basic cleaning
            return cleanPhoneString(phone);
        }
        
        return null;
    }
    
    private static String cleanPhoneString(String phone) {
        if (phone == null) return "";
        
        return phone.replaceAll("[^\\d+]", "")
                   .replaceAll("^\\+?0+", "") // Remove leading zeros
                   .trim();
    }
    
    private static boolean isValidPhoneLength(String phone) {
        if (phone == null) return false;
        
        String digitsOnly = phone.replaceAll("[^\\d]", "");
        return digitsOnly.length() >= 7 && digitsOnly.length() <= 15;
    }
    
    private static boolean isFalsePositive(String phone) {
        String digitsOnly = phone.replaceAll("[^\\d]", "");
        return FALSE_POSITIVES.contains(digitsOnly) || 
               digitsOnly.matches("^123\\d+") || 
               digitsOnly.matches("^555\\d+");
    }

    public static String cleanAndFormatPhone(String phone) {
        if (phone == null) return "";
        
        String cleaned = cleanPhoneString(phone);
        if (cleaned.length() < 7 || cleaned.length() > 15) return "";
        
        try {
            // Try to format using libphonenumber
            Phonenumber.PhoneNumber number = phoneUtil.parse(cleaned, "US");
            if (phoneUtil.isValidNumber(number)) {
                return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
            }
        } catch (Exception e) {
            // Fall back to basic formatting
        }
        
        // Basic formatting for US numbers
        if (cleaned.length() == 10) {
            return "+1 (" + cleaned.substring(0, 3) + ") " + cleaned.substring(3, 6) + "-" + cleaned.substring(6);
        } else if (cleaned.length() == 11 && cleaned.startsWith("1")) {
            return "+1 (" + cleaned.substring(1, 4) + ") " + cleaned.substring(4, 7) + "-" + cleaned.substring(7);
        }
        
        return cleaned;
    }
}