package com.example.companyScraper.util;

import java.util.*;
import java.util.regex.*;

public class GitHubExtractor {
    public static Set<String> extractGitHubUrls(String html) {
        Set<String> urls = new HashSet<>();
        Matcher m = Pattern.compile("https?://(www\\.)?github\\.com/[^\"'? >]+").matcher(html);
        while (m.find()) urls.add(m.group().split("\\?")[0].replaceAll("/$", "")); // remove query & trailing slash
        return urls;
    }
}
