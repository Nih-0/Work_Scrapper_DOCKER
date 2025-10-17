package com.example.companyScraper.model;

import com.example.companyScraper.util.Scraper;
import java.util.*;

public class ScrapeResult {
    private String url;
    private String status;
    private List<String> emails;
    private List<String> phones;
    private List<String> linkedinUrls;
    private List<String> githubUrls;
    private List<String> facebookUrls;
    private List<Person> people;
    private String notes;

    public ScrapeResult(String url) { 
        this.url = url;
        this.emails = new ArrayList<>();
        this.phones = new ArrayList<>();
        this.linkedinUrls = new ArrayList<>();
        this.githubUrls = new ArrayList<>();
        this.facebookUrls = new ArrayList<>();
        this.people = new ArrayList<>();
    }

    public static ScrapeResult fromResult(Scraper.Result result) {
        ScrapeResult r = new ScrapeResult(result.getUrl());
        r.setStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
        r.setEmails(result.getEmails());
        r.setPhones(result.getPhones());
        r.setLinkedinUrls(result.getLinkedinUrls());
        r.setGithubUrls(result.getGithubUrls());
        r.setFacebookUrls(result.getFacebookUrls());
        r.setPeople(convertPeople(result.getPeople()));
        r.setNotes(result.getNotes());
        return r;
    }

    private static List<Person> convertPeople(List<Scraper.Person> scraperPeople) {
        if (scraperPeople == null) return new ArrayList<>();
        
        List<Person> people = new ArrayList<>();
        for (Scraper.Person scraperPerson : scraperPeople) {
            Person person = new Person();
            person.setFirstName(scraperPerson.getFirstName());
            person.setLastName(scraperPerson.getLastName());
            person.setRole(scraperPerson.getRole());
            person.setEmail(scraperPerson.getEmail());
            person.setPhone(scraperPerson.getPhone());
            people.add(person);
        }
        return people;
    }

    // Getters and setters - FIXED to handle both Set and List
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public List<String> getEmails() { return emails; }
    public void setEmails(Collection<String> emails) { 
        this.emails = emails != null ? new ArrayList<>(emails) : new ArrayList<>(); 
    }
    
    public List<String> getPhones() { return phones; }
    public void setPhones(Collection<String> phones) { 
        this.phones = phones != null ? new ArrayList<>(phones) : new ArrayList<>(); 
    }
    
    public List<String> getLinkedinUrls() { return linkedinUrls; }
    public void setLinkedinUrls(Collection<String> linkedinUrls) { 
        this.linkedinUrls = linkedinUrls != null ? new ArrayList<>(linkedinUrls) : new ArrayList<>(); 
    }
    
    public List<String> getGithubUrls() { return githubUrls; }
    public void setGithubUrls(Collection<String> githubUrls) { 
        this.githubUrls = githubUrls != null ? new ArrayList<>(githubUrls) : new ArrayList<>(); 
    }
    
    public List<String> getFacebookUrls() { return facebookUrls; }
    public void setFacebookUrls(Collection<String> facebookUrls) { 
        this.facebookUrls = facebookUrls != null ? new ArrayList<>(facebookUrls) : new ArrayList<>(); 
    }
    
    public List<Person> getPeople() { return people; }
    public void setPeople(List<Person> people) { 
        this.people = people != null ? people : new ArrayList<>(); 
    }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    // Person inner class
    public static class Person {
        private String firstName;
        private String lastName;
        private String role;
        private String email;
        private String phone;

        public Person() {}

        public Person(String firstName, String lastName, String role) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.role = role;
        }

        // Getters and setters
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        @Override
        public String toString() {
            return String.format("%s %s (%s)", firstName, lastName, role);
        }
    }
}