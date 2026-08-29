package com.llmops.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);

    private static final String BUDGET_PREFIX = "token:budget:";
    private static final String ACTIVE_USERS_KEY = "token:budget:active";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final int maxTokens;
    private final int refillIntervalSeconds;
    private final int refillAmount; // Tokens to refill per interval

    public TokenBudgetService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${token-budget.max-tokens:50000}") int maxTokens,
            @Value("${token-budget.refill-hours:5}") int refillHours,
            @Value("${token-budget.refill-interval-seconds:30}") int refillIntervalSeconds) {
        
        this.redisTemplate = redisTemplate;
        this.maxTokens = maxTokens;
        this.refillIntervalSeconds = refillIntervalSeconds;
        
        // Calculate how many tokens to add every interval to achieve full refill in 'refillHours'
        long totalSeconds = refillHours * 3600L;
        long intervals = totalSeconds / refillIntervalSeconds;
        this.refillAmount = (int) Math.max(1, maxTokens / intervals);
        
        log.info("TokenBudgetService initialized: MaxTokens={}, RefillHours={}, RefillInterval={}s ({} tokens/interval)",
                maxTokens, refillHours, refillIntervalSeconds, refillAmount);
    }

    /**
     * Initializes a full budget for a new user and marks them as active.
     */
    public Mono<Void> initializeBudget(String userId) {
        String key = BUDGET_PREFIX + userId;
        return redisTemplate.opsForValue().set(key, String.valueOf(maxTokens))
                .then(redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, userId))
                .then();
    }

    /**
     * Returns the remaining tokens for the user. If missing, initializes it.
     */
    public Mono<Integer> getRemainingBudget(String userId) {
        String key = BUDGET_PREFIX + userId;
        return redisTemplate.opsForValue().get(key)
                .map(Integer::parseInt)
                .switchIfEmpty(
                        initializeBudget(userId).then(Mono.just(maxTokens))
                );
    }

    /**
     * Deducts estimated tokens based on word count. (1 word ≈ 1.3 tokens).
     */
    public Mono<Integer> deductTokens(String userId, String prompt, String answer) {
        int promptWords = (prompt == null || prompt.isBlank()) ? 0 : prompt.trim().split("\\s+").length;
        int answerWords = (answer == null || answer.isBlank()) ? 0 : answer.trim().split("\\s+").length;
        
        int estimatedTokens = (int) Math.ceil((promptWords + answerWords) * 1.3);
        if (estimatedTokens == 0) {
            return getRemainingBudget(userId); // No deduction
        }

        String key = BUDGET_PREFIX + userId;
        return redisTemplate.opsForValue().increment(key, -estimatedTokens)
                .map(Long::intValue)
                .doOnNext(remaining -> log.debug("Deducted {} tokens for user {}. Remaining: {}", estimatedTokens, userId, remaining));
    }

    /**
     * Computes how many seconds until the budget is fully restored.
     */
    public int estimateSecondsToReset(int currentTokens) {
        if (currentTokens >= maxTokens) return 0;
        int deficit = maxTokens - currentTokens;
        int intervalsNeeded = (int) Math.ceil((double) deficit / refillAmount);
        return intervalsNeeded * refillIntervalSeconds;
    }
    
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Background task that runs every N seconds to refill active users.
     */
    @Scheduled(fixedRateString = "${token-budget.refill-interval-seconds:30}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void refillBudgets() {
        // Lua script: INCRBY, then limit to maxTokens.
        String script = 
                "local current = redis.call('GET', KEYS[1]); " +
                "if current then " +
                "  local num = tonumber(current); " +
                "  if num < tonumber(ARGV[1]) then " +
                "    local next_val = num + tonumber(ARGV[2]); " +
                "    if next_val > tonumber(ARGV[1]) then next_val = tonumber(ARGV[1]) end; " +
                "    redis.call('SET', KEYS[1], tostring(next_val)); " +
                "    return next_val; " +
                "  end; " +
                "  return num; " +
                "else " +
                "  return -1; " +
                "end;";
                
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

        redisTemplate.opsForSet().members(ACTIVE_USERS_KEY)
                .flatMap(userId -> {
                    String key = BUDGET_PREFIX + userId;
                    return redisTemplate.execute(redisScript, List.of(key), List.of(String.valueOf(maxTokens), String.valueOf(refillAmount)));
                })
                .subscribe(
                        result -> {}, 
                        error -> log.error("Error during budget refill", error)
                );
    }
}
