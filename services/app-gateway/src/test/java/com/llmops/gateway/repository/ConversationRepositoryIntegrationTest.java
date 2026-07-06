package com.llmops.gateway.repository;

import com.llmops.gateway.entity.Conversation;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Disabled("Disabled because local Docker daemon is not active. Enable in environment with active Docker daemon.")
public class ConversationRepositoryIntegrationTest {

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("chatbot_db")
            .withUsername("postgres")
            .withPassword("password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ConversationRepository conversationRepository;

    @Test
    public void testSaveAndFindConversation() {
        Conversation conversation = new Conversation();
        conversation.setId("test-session-id");
        conversation.setTitle("Test Chat Session");

        conversationRepository.save(conversation);

        Optional<Conversation> found = conversationRepository.findById("test-session-id");
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Test Chat Session");
    }
}
