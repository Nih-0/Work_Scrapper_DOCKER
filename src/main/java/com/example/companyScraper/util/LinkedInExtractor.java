package com.example.companyScraper.util;

import java.util.*;
import java.util.regex.*;

public class LinkedInExtractor {
    public static Set<String> extractLinkedInUrls(String html) {
        Set<String> urls = new HashSet<>();
        Matcher m = Pattern.compile("https?://([a-z]{2,3}\\.)?linkedin\\.com/in/[^\"'? >]+").matcher(html);
        while (m.find()) urls.add(m.group().split("\\?")[0]); // remove query params
        return urls;
    }
}
