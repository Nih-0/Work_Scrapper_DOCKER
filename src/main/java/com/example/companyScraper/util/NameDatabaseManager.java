package com.example.companyScraper.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NameDatabaseManager {
    
    private final Map<String, Set<String>> firstNameDatabase = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> lastNameDatabase = new ConcurrentHashMap<>();
    private final Set<String> commonRoles = ConcurrentHashMap.newKeySet();
    private boolean isLoaded = false;
    
    // Common name prefixes and suffixes for validation
    private static final Set<String> NAME_PREFIXES = Set.of("mr", "mrs", "ms", "dr", "prof");
    private static final Set<String> NAME_SUFFIXES = Set.of("jr", "sr", "ii", "iii", "iv", "phd", "md");
    
    public void loadNameDatabase(String filePath) {
        try (InputStream inputStream = new ClassPathResource(filePath).getInputStream();
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();
            
            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();
            
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                processNameRow(row);
            }
            
            isLoaded = true;
            System.out.println("Name database loaded: " + firstNameDatabase.size() + " first names, " + 
                             lastNameDatabase.size() + " last names, " + commonRoles.size() + " roles");
            
        } catch (Exception e) {
            System.err.println("Failed to load name database: " + e.getMessage());
            loadFallbackNames();
        }
    }
    
    private void processNameRow(Row row) {
        try {
             
            String firstName = getCellValue(row.getCell(0));
            System.out.println("Processing first name: " + firstName);
            if (isValidName(firstName)) {
                String normalizedFirstName = firstName.toLowerCase();
                firstNameDatabase.computeIfAbsent(normalizedFirstName, k -> new HashSet<>()).add(firstName);
            }
            
            
            String lastName = getCellValue(row.getCell(1));
            if (isValidName(lastName)) {
                String normalizedLastName = lastName.toLowerCase();
                lastNameDatabase.computeIfAbsent(normalizedLastName, k -> new HashSet<>()).add(lastName);
            }
            
             
            String role = getCellValue(row.getCell(2));
            if (isValidRole(role)) {
                commonRoles.add(role.toLowerCase());
            }
            
             
            String variations = getCellValue(row.getCell(3));
            if (variations != null && !variations.isEmpty()) {
                for (String variation : variations.split(",")) {
                    String trimmed = variation.trim();
                    if (isValidName(trimmed)) {
                        String normalizedVariation = trimmed.toLowerCase();
                        firstNameDatabase.computeIfAbsent(normalizedVariation, k -> new HashSet<>()).add(trimmed);
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error processing name row: " + e.getMessage());
        }
    }
    
    private String getCellValue(Cell cell) {
        if (cell == null) return null;
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf((long) cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return null;
        }
    }
    
    private boolean isValidName(String name) {
        if (name == null || name.length() < 2 || name.length() > 20) return false;
        
        
        String lowerName = name.toLowerCase();
        if (NAME_PREFIXES.contains(lowerName) || NAME_SUFFIXES.contains(lowerName)) {
            return false;
        }
        
        
        return name.matches("[a-zA-Z\\-']+");
    }
    
    private boolean isValidRole(String role) {
        if (role == null || role.length() < 2) return false;
        return role.matches("[a-zA-Z\\s\\-&]+");
    }
    
    private void loadFallbackNames() {
        // Load common first names - FIXED: Use List instead of String[]
        List<String> commonFirstNames = Arrays.asList(
            "John", "Jane", "Michael", "Sarah", "David", "Emily", "James", "Jessica", 
            "Robert", "Jennifer", "William", "Elizabeth", "Richard", "Susan"
        );
        
        List<String> commonLastNames = Arrays.asList(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Garcia",
            "Rodriguez", "Wilson", "Martinez", "Anderson", "Taylor", "Thomas"
        );
        
        List<String> commonRolesList = Arrays.asList(
            "CEO", "CTO", "CFO", "Manager", "Director", "President", "Founder", "Developer",
            "Designer", "Analyst", "Engineer", "Specialist", "Consultant"
        );
        
        for (String name : commonFirstNames) {
            String normalized = name.toLowerCase();
            firstNameDatabase.computeIfAbsent(normalized, k -> new HashSet<>()).add(name);
        }
        
        for (String name : commonLastNames) {
            String normalized = name.toLowerCase();
            lastNameDatabase.computeIfAbsent(normalized, k -> new HashSet<>()).add(name);
        }
        
        for (String role : commonRolesList) {
            commonRoles.add(role.toLowerCase());
        }
        
        isLoaded = true;
        System.out.println("Fallback name database loaded");
    }
    
    public boolean isNameInDatabase(String name) {
        if (!isLoaded || name == null) return false;
        
        String cleanName = name.trim().toLowerCase();
        return firstNameDatabase.containsKey(cleanName) || lastNameDatabase.containsKey(cleanName);
    }
    
    public String getCanonicalName(String name) {
        if (!isLoaded || name == null) return name;
        
        String cleanName = name.trim().toLowerCase();
        Set<String> firstNames = firstNameDatabase.get(cleanName);
        if (firstNames != null && !firstNames.isEmpty()) {
            return firstNames.iterator().next();
        }
        
        Set<String> lastNames = lastNameDatabase.get(cleanName);
        if (lastNames != null && !lastNames.isEmpty()) {
            return lastNames.iterator().next();
        }
        
        return name;
    }
    
    public boolean isCommonRole(String role) {
        if (!isLoaded || role == null) return false;
        return commonRoles.contains(role.trim().toLowerCase());
    }
    
    public Set<String> getAllFirstNames() {
        Set<String> allNames = new HashSet<>();
        for (Set<String> names : firstNameDatabase.values()) {
            allNames.addAll(names);
        }
        return allNames;
    }
    
    public Set<String> getAllLastNames() {
        Set<String> allNames = new HashSet<>();
        for (Set<String> names : lastNameDatabase.values()) {
            allNames.addAll(names);
        }
        return allNames;
    }
    
    public boolean isDatabaseLoaded() {
        return isLoaded;
    }
}