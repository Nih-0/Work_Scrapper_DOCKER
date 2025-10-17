package com.example.companyScraper.util;

import java.util.*;
import java.util.regex.Pattern;

public class FacebookExtractor {
    
    // More comprehensive Facebook URL patterns
    private static final List<Pattern> FACEBOOK_PATTERNS = Arrays.asList(
        Pattern.compile("https?://(?:www\\.)?facebook\\.com/([a-zA-Z0-9.]+)(?:/)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://(?:www\\.)?fb\\.com/([a-zA-Z0-9.]+)(?:/)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://(?:www\\.)?facebook\\.com/profile\\.php\\?id=(\\d+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://(?:web\\.)?facebook\\.com/([a-zA-Z0-9.]+)(?:/)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://(?:m\\.)?facebook\\.com/([a-zA-Z0-9.]+)(?:/)?", Pattern.CASE_INSENSITIVE)
    );
    
    // Common Facebook pages to exclude
    private static final Set<String> COMMON_PAGES = Set.of(
        "facebook.com/home", "facebook.com/login", "facebook.com/signup",
        "facebook.com/about", "facebook.com/help", "facebook.com/policies",
        "facebook.com/legal", "facebook.com/terms", "facebook.com/privacy",
        "facebook.com/careers", "facebook.com/business"
    );
    
    // Common profile indicators in link text
    private static final Set<String> PROFILE_INDICATORS = Set.of(
        "profile", "timeline", "wall", "friend", "follow", "like"
    );

    public static Set<String> extractFacebookUrls(String html) {
        Set<String> urls = new HashSet<>();
        
        // Extract using multiple patterns
        for (Pattern pattern : FACEBOOK_PATTERNS) {
            var matcher = pattern.matcher(html);
            while (matcher.find()) {
                String url = normalizeFacebookUrl(matcher.group());
                if (isValidProfileUrl(url)) {
                    urls.add(url);
                }
            }
        }
        
        // Enhanced context-based extraction
        urls.addAll(extractFromLinkContext(html));
        
        return urls;
    }
    
    private static Set<String> extractFromLinkContext(String html) {
        Set<String> urls = new HashSet<>();
        
        // Look for Facebook links with profile-like context
        Pattern linkPattern = Pattern.compile(
            "<a[^>]+href=\"(https?://(?:www\\.)?facebook\\.com/[^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        );
        
        var matcher = linkPattern.matcher(html);
        while (matcher.find()) {
            String url = normalizeFacebookUrl(matcher.group(1));
            String linkText = matcher.group(2).toLowerCase();
            
            // Check if link text suggests it's a profile
            if (isProfileLink(linkText) && isValidProfileUrl(url)) {
                urls.add(url);
            }
        }
        
        return urls;
    }
    
    private static String normalizeFacebookUrl(String url) {
        if (url == null) return "";
        
        // Remove query parameters and fragments
        url = url.split("[?#]")[0];
        // Remove trailing slash
        url = url.replaceAll("/$", "");
        
        return url;
    }
    
    private static boolean isValidProfileUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        
        String lowerUrl = url.toLowerCase();
        
        // Exclude common Facebook pages
        if (COMMON_PAGES.stream().anyMatch(lowerUrl::contains)) {
            return false;
        }
        
        // Check URL structure for profile indicators
        String path = lowerUrl.substring(lowerUrl.indexOf("facebook.com/") + 13);
        if (path.isEmpty()) return false;
        
        // Exclude pages with specific patterns
        if (path.matches("(pages|groups|events|hashtag)/.*")) {
            return false;
        }
        
        // Profile URLs typically don't have multiple slashes after the username
        if (path.contains("/") && !path.contains("/posts/") && !path.contains("/photos/")) {
            return false;
        }
        
        return true;
    }
    
    private static boolean isProfileLink(String linkText) {
        if (linkText == null || linkText.isEmpty()) return false;
        
        String text = linkText.toLowerCase();
        
        // Check for personal name patterns (first and last name)
        if (text.matches("[a-z]+ [a-z]+")) {
            return true;
        }
        
        // Check for profile indicators in link text
        return PROFILE_INDICATORS.stream().anyMatch(text::contains);
    }
}