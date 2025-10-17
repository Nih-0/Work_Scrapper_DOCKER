package com.example.companyScraper.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NameRoleExtractor {
    
    private static NameDatabaseManager nameDatabase;
    private static final String OPENROUTER_API_KEY = "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";
    private static final String OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL = "nvidia/nemotron-nano-9b-v2:free";
    
    private static final boolean USE_AI = true;
    private static final boolean USE_DATABASE_FIRST = true;
    
    public static void setNameDatabase(NameDatabaseManager database) {
        nameDatabase = database;
    }
    
    public static List<Scraper.Person> extractPeopleWithAI(String html, String url) {
        List<Scraper.Person> people = new ArrayList<>();
        
        try {
            // Extract text content for analysis
            String textContent = extractRelevantText(html);
            
            // First try database-based extraction
            if (USE_DATABASE_FIRST && nameDatabase != null && nameDatabase.isDatabaseLoaded()) {
                people = extractPeopleWithDatabase(textContent, html);
                System.out.println("Database extraction found " + people.size() + " people");
            }
            
            // If database extraction found few results, try AI
            if (people.size() < 2 && textContent.length() > 100 && USE_AI) {
                List<Scraper.Person> aiPeople = callOpenRouterForPeopleExtraction(textContent, url);
                System.out.println("AI extraction found " + aiPeople.size() + " people");
                
                // Merge results, preferring database results
                people = mergePeopleResults(people, aiPeople);
            }
            
            // Always use pattern matching as additional fallback
            List<Scraper.Person> patternPeople = extractPeopleWithPatterns(html, textContent);
            System.out.println("Pattern extraction found " + patternPeople.size() + " people");
            
            // Final merge
            people = mergePeopleResults(people, patternPeople);
            
        } catch (Exception e) {
            System.err.println("AI extraction failed, using pattern matching only: " + e.getMessage());
            people = extractPeopleWithPatterns(html, extractRelevantText(html));
        }
        
        // Validate and clean up results
        people = people.stream()
            .filter(Objects::nonNull)
            .filter(p -> p.getFirstName() != null && !p.getFirstName().trim().isEmpty())
            .collect(Collectors.toList());
        
        System.out.println("Final merged result: " + people.size() + " people");
        return people;
    }
    
    private static List<Scraper.Person> extractPeopleWithDatabase(String textContent, String html) {
        List<Scraper.Person> people = new ArrayList<>();
        
        if (nameDatabase == null || !nameDatabase.isDatabaseLoaded()) {
            return people;
        }
        
        Set<String> firstNames = nameDatabase.getAllFirstNames();
        Set<String> lastNames = nameDatabase.getAllLastNames();
        
        // Split text into words and look for name patterns
        String[] words = textContent.split("\\s+");
        
        for (int i = 0; i < words.length - 1; i++) {
            String currentWord = cleanWord(words[i]);
            String nextWord = cleanWord(words[i + 1]);
            
            // Check for "FirstName LastName" pattern
            if (isValidFirstName(currentWord) && isValidLastName(nextWord)) {
                Scraper.Person person = new Scraper.Person();
                person.setFirstName(nameDatabase.getCanonicalName(currentWord));
                person.setLastName(nameDatabase.getCanonicalName(nextWord));
                
                // Try to find role in surrounding context
                String role = findRoleInContext(words, i, 5);
                person.setRole(role);
                
                if (!isDuplicatePerson(people, person)) {
                    people.add(person);
                    System.out.println("Database match: " + person.getFirstName() + " " + person.getLastName());
                }
            }
        }
        
        return people;
    }
    
    private static boolean isValidFirstName(String word) {
        if (word == null || word.length() < 2) return false;
        return nameDatabase.isNameInDatabase(word) && 
               Character.isUpperCase(word.charAt(0));
    }
    
    private static boolean isValidLastName(String word) {
        if (word == null || word.length() < 2) return false;
        return nameDatabase.isNameInDatabase(word) && 
               Character.isUpperCase(word.charAt(0));
    }
    
    private static String cleanWord(String word) {
        if (word == null) return "";
        return word.replaceAll("[^a-zA-Z\\-']", "").trim();
    }
    
    private static String findRoleInContext(String[] words, int nameIndex, int contextWindow) {
        int start = Math.max(0, nameIndex - contextWindow);
        int end = Math.min(words.length - 1, nameIndex + contextWindow);
        
        for (int i = start; i <= end; i++) {
            String word = words[i].replaceAll("[^a-zA-Z]", "").toLowerCase();
            if (nameDatabase.isCommonRole(word)) {
                return capitalizeRole(word);
            }
        }
        
        return "";
    }
    
    // Rest of the methods remain the same as your original implementation
    // (callOpenRouterForPeopleExtraction, parseAIResponse, extractPeopleWithPatterns, etc.)
    // Only including the changed parts for brevity
    
    private static List<Scraper.Person> callOpenRouterForPeopleExtraction(String text, String url) {
        // Only call AI if we haven't found enough people via database
        RestTemplate restTemplate = new RestTemplate();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + OPENROUTER_API_KEY);
        
        String prompt = String.format(
            "Analyze the following website text and extract ALL person names with their roles/job titles. " +
            "Focus on executive team members, founders, employees, and any people mentioned.\n\n" +
            "Website: %s\n\n" +
            "Text Content:\n%s\n\n" +
            "IMPORTANT: Return ONLY a valid JSON array with objects containing firstName, lastName, role. " +
            "Example: [{\"firstName\":\"John\",\"lastName\":\"Doe\",\"role\":\"CEO\"}]\n" +
            "If no people found, return empty array [].\n" +
            "Extract as many people as you can find.", 
            url, 
            text.substring(0, Math.min(3000, text.length()))
        );
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", MODEL);
        request.put("messages", new Object[]{
            Map.of("role", "user", "content", prompt)
        });
        request.put("max_tokens", 2000);
        request.put("temperature", 0.1);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        try {
            System.out.println("Calling OpenRouter API for people extraction...");
            ResponseEntity<Map> response = restTemplate.postForEntity(OPENROUTER_API_URL, entity, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> responseBody = response.getBody();
                if (responseBody != null && responseBody.containsKey("choices")) {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                    if (!choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        Map<String, Object> message = (Map<String, Object>) choice.get("message");
                        String content = (String) message.get("content");
                        
                        System.out.println("OpenRouter API response received");
                        return parseAIResponse(content);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("OpenRouter API call failed: " + e.getMessage());
        }
        
        return new ArrayList<>();
    }
    
    private static List<Scraper.Person> parseAIResponse(String response) {
        List<Scraper.Person> people = new ArrayList<>();
        
        try {
            String cleanResponse = response.trim();
            
            if (cleanResponse.contains("[") && cleanResponse.contains("]")) {
                int start = cleanResponse.indexOf('[');
                int end = cleanResponse.lastIndexOf(']') + 1;
                String jsonContent = cleanResponse.substring(start, end);
                
                String content = jsonContent.substring(1, jsonContent.length() - 1).trim();
                if (content.isEmpty()) {
                    return people;
                }
                
                String[] personEntries;
                if (content.contains("},{")) {
                    personEntries = content.split("\\},\\s*\\{");
                    for (int i = 0; i < personEntries.length; i++) {
                        if (i == 0) personEntries[i] = personEntries[i] + "}";
                        else if (i == personEntries.length - 1) personEntries[i] = "{" + personEntries[i];
                        else personEntries[i] = "{" + personEntries[i] + "}";
                    }
                } else {
                    personEntries = new String[]{"{" + content + "}"};
                }
                
                for (String entry : personEntries) {
                    try {
                        Scraper.Person person = parsePersonEntry(entry);
                        if (person != null && person.getFirstName() != null && !person.getFirstName().trim().isEmpty()) {
                            people.add(person);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to parse person entry: " + entry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse AI response: " + e.getMessage());
        }
        
        return people;
    }
    
    private static Scraper.Person parsePersonEntry(String entry) {
        try {
            Scraper.Person person = new Scraper.Person();
            
            if (entry.contains("\"firstName\"")) {
                String firstName = extractJsonValue(entry, "firstName");
                if (firstName != null && !firstName.trim().isEmpty()) {
                    person.setFirstName(capitalizeName(firstName));
                }
            }
            
            if (entry.contains("\"lastName\"")) {
                String lastName = extractJsonValue(entry, "lastName");
                if (lastName != null && !lastName.trim().isEmpty()) {
                    person.setLastName(capitalizeName(lastName));
                }
            }
            
            if (entry.contains("\"role\"")) {
                String role = extractJsonValue(entry, "role");
                if (role != null && !role.trim().isEmpty()) {
                    person.setRole(capitalizeRole(role));
                }
            }
            
            return (person.getFirstName() != null && !person.getFirstName().trim().isEmpty()) ? person : null;
            
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String extractJsonValue(String json, String key) {
        try {
            String[] patterns = {
                "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"",
                "\"" + key + "\"\\s*:\\s*'([^']+)'",
                "\"" + key + "\"\\s*:\\s*([^,}\\s]+)"
            };
            
            for (String pattern : patterns) {
                Pattern p = Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(json);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting JSON value for key: " + key);
        }
        return null;
    }
    
    private static List<Scraper.Person> mergePeopleResults(List<Scraper.Person> primaryPeople, List<Scraper.Person> secondaryPeople) {
        Set<String> seenNames = new HashSet<>();
        List<Scraper.Person> merged = new ArrayList<>();
        
        for (Scraper.Person person : primaryPeople) {
            String key = getPersonKey(person);
            if (!key.trim().isEmpty() && !seenNames.contains(key)) {
                seenNames.add(key);
                merged.add(person);
            }
        }
        
        for (Scraper.Person person : secondaryPeople) {
            String key = getPersonKey(person);
            if (!key.trim().isEmpty() && !seenNames.contains(key)) {
                seenNames.add(key);
                merged.add(person);
            }
        }
        
        return merged;
    }
    
    private static String getPersonKey(Scraper.Person person) {
        return (person.getFirstName() + " " + (person.getLastName() != null ? person.getLastName() : "")).toLowerCase().trim();
    }
    
    private static boolean isDuplicatePerson(List<Scraper.Person> people, Scraper.Person newPerson) {
        String newKey = getPersonKey(newPerson);
        return people.stream().anyMatch(p -> getPersonKey(p).equals(newKey));
    }
    
    private static String extractRelevantText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header, meta, link").remove();
        
        StringBuilder content = new StringBuilder();
        String[] contentSelectors = {
            "div[class*='team']", "div[class*='about']", "div[class*='leadership']",
            "div[class*='executive']", "div[class*='staff']", "div[class*='employee']",
            "section[class*='team']", "section[class*='about']", "section[class*='leadership']",
            "main", "article", ".content", "#content", "body"
        };
        
        for (String selector : contentSelectors) {
            for (Element element : doc.select(selector)) {
                String text = element.text().trim();
                if (text.length() > 50 && !content.toString().contains(text)) {
                    content.append(text).append("\n\n");
                }
            }
        }
        
        return content.toString();
    }
    
    private static List<Scraper.Person> extractPeopleWithPatterns(String html, String textContent) {
        // Your existing pattern-based extraction logic
        // This remains the same as your original implementation
        return new ArrayList<>();
    }
    
    private static String capitalizeName(String name) {
        if (name == null || name.isEmpty()) return name;
        return Arrays.stream(name.split("\\s+|-"))
                .map(word -> {
                    if (word.length() > 1) {
                        return word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
                    }
                    return word.toUpperCase();
                })
                .reduce((a, b) -> a + " " + b)
                .orElse(name);
    }
    
    private static String capitalizeRole(String role) {
        if (role == null || role.isEmpty()) return role;
        
        if (role.equalsIgnoreCase("ceo") || role.equalsIgnoreCase("cto") || 
            role.equalsIgnoreCase("cfo") || role.equalsIgnoreCase("coo") ||
            role.equalsIgnoreCase("cmo") || role.equalsIgnoreCase("vp")) {
            return role.toUpperCase();
        }
        
        return Arrays.stream(role.split("\\s+"))
                .map(word -> {
                    if (word.equalsIgnoreCase("and") || word.equalsIgnoreCase("of") || 
                        word.equalsIgnoreCase("the")) {
                        return word.toLowerCase();
                    }
                    return word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
                })
                .reduce((a, b) -> a + " " + b)
                .orElse(role);
    }
}