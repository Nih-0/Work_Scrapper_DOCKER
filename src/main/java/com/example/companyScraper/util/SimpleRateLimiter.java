package com.example.companyScraper.util;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Simple rate limiter using Google Guava RateLimiter
 * Alternative to Bucket4j for controlling request rates
 */
@Component
public class SimpleRateLimiter {
    
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastCleanup = new ConcurrentHashMap<>();
    
    // Default rates
    private static final double DEFAULT_REQUESTS_PER_SECOND = 2.0;
    private static final double DOMAIN_REQUESTS_PER_SECOND = 0.5; // More conservative for domains
    private static final long CLEANUP_INTERVAL_MS = 60000; // Clean up old limiters every minute
    
    /**
     * Acquire permission for a general request
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) {
        RateLimiter limiter = getGlobalLimiter();
        return limiter.tryAcquire(timeout, unit);
    }
    
    /**
     * Acquire permission for a domain-specific request
     */
    public boolean tryAcquireForDomain(String domain, long timeout, TimeUnit unit) {
        if (domain == null || domain.isEmpty()) {
            return tryAcquire(timeout, unit);
        }
        
        RateLimiter limiter = getDomainLimiter(domain);
        boolean acquired = limiter.tryAcquire(timeout, unit);
        
        // Cleanup old limiters periodically
        cleanupOldLimiters();
        
        return acquired;
    }
    
    /**
     * Block until permission is available for a domain
     */
    public void acquireForDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            getGlobalLimiter().acquire();
            return;
        }
        
        getDomainLimiter(domain).acquire();
        cleanupOldLimiters();
    }
    
    /**
     * Block until permission is available
     */
    public void acquire() {
        getGlobalLimiter().acquire();
    }
    
    /**
     * Get or create a rate limiter for a specific domain
     */
    private RateLimiter getDomainLimiter(String domain) {
        return limiters.computeIfAbsent(domain.toLowerCase(), 
            k -> RateLimiter.create(DOMAIN_REQUESTS_PER_SECOND));
    }
    
    /**
     * Get the global rate limiter
     */
    private RateLimiter getGlobalLimiter() {
        return limiters.computeIfAbsent("__global__", 
            k -> RateLimiter.create(DEFAULT_REQUESTS_PER_SECOND));
    }
    
    /**
     * Update rate for a specific domain
     */
    public void updateDomainRate(String domain, double requestsPerSecond) {
        if (domain == null || domain.isEmpty()) return;
        
        RateLimiter limiter = limiters.get(domain.toLowerCase());
        if (limiter != null) {
            limiter.setRate(requestsPerSecond);
        } else {
            limiters.put(domain.toLowerCase(), RateLimiter.create(requestsPerSecond));
        }
    }
    
    /**
     * Update global rate
     */
    public void updateGlobalRate(double requestsPerSecond) {
        RateLimiter limiter = limiters.get("__global__");
        if (limiter != null) {
            limiter.setRate(requestsPerSecond);
        } else {
            limiters.put("__global__", RateLimiter.create(requestsPerSecond));
        }
    }
    
    /**
     * Clean up old limiters that haven't been used recently
     */
    private void cleanupOldLimiters() {
        long now = System.currentTimeMillis();
        String cleanupKey = "lastCleanup";
        
        Long lastCleanupTime = lastCleanup.get(cleanupKey);
        if (lastCleanupTime == null || (now - lastCleanupTime) > CLEANUP_INTERVAL_MS) {
            // Simple cleanup - remove limiters for domains we haven't seen recently
            // In a real implementation, you might want more sophisticated tracking
            if (limiters.size() > 100) { // Only cleanup if we have many limiters
                limiters.entrySet().removeIf(entry -> 
                    !entry.getKey().equals("__global__") && 
                    Math.random() < 0.1 // Randomly remove 10% of domain limiters
                );
            }
            lastCleanup.put(cleanupKey, now);
        }
    }
    
    /**
     * Get current statistics
     */
    public RateLimiterStats getStats() {
        return new RateLimiterStats(
            limiters.size(),
            limiters.containsKey("__global__") ? limiters.get("__global__").getRate() : 0,
            DOMAIN_REQUESTS_PER_SECOND
        );
    }
    
    /**
     * Reset all limiters
     */
    public void reset() {
        limiters.clear();
        lastCleanup.clear();
    }
    
    public static class RateLimiterStats {
        public final int totalLimiters;
        public final double globalRate;
        public final double defaultDomainRate;
        
        public RateLimiterStats(int totalLimiters, double globalRate, double defaultDomainRate) {
            this.totalLimiters = totalLimiters;
            this.globalRate = globalRate;
            this.defaultDomainRate = defaultDomainRate;
        }
        
        @Override
        public String toString() {
            return String.format("RateLimiterStats{limiters=%d, globalRate=%.2f/s, domainRate=%.2f/s}", 
                totalLimiters, globalRate, defaultDomainRate);
        }
    }
}