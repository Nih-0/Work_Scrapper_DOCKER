/*
 * package com.example.companyScraper.controller;
 * 
 * import com.example.companyScraper.model.ScrapeResult; import
 * com.example.companyScraper.service.ScraperService; import
 * com.example.companyScraper.util.CsvExporter; import
 * com.example.companyScraper.util.InputReader; import
 * org.springframework.http.ResponseEntity; import
 * org.springframework.web.bind.annotation.*; import
 * org.springframework.web.multipart.MultipartFile;
 * 
 * import java.util.List;
 * 
 * @RestController
 * 
 * @RequestMapping("/api")
 * 
 * @CrossOrigin(origins = "http://localhost:5173") public class
 * ScraperController {
 * 
 * private final ScraperService scraperService;
 * 
 * public ScraperController(ScraperService scraperService) { this.scraperService
 * = scraperService; }
 * 
 * @PostMapping("/urls/upload") public ResponseEntity<List<ScrapeResult>>
 * uploadFile(@RequestParam("file") MultipartFile file) throws Exception {
 * InputReader reader = new InputReader(); List<String> urls =
 * reader.readUrls(file.getInputStream());
 * 
 * List<ScrapeResult> results = scraperService.scrapeUrls(urls);
 * 
 * CsvExporter.exportToCsv(results, "scraped-results.csv");
 * 
 * return ResponseEntity.ok(results); } }
 */