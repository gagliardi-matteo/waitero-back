package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.LLMResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LLMService {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final ObjectMapper objectMapper;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4.1-mini}")
    private String openAiModel;

    public LLMResponse extract(String nomePiatto) {
        String normalizedInput = normalize(nomePiatto);
        if (normalizedInput.isEmpty()) {
            throw new IllegalArgumentException("Il nome del piatto e obbligatorio");
        }
        if (openAiApiKey == null || openAiApiKey.trim().isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY non configurata");
        }

        log.info("OpenAI normalization request for dish: {} with model {}", normalizedInput, openAiModel);

        RestClient client = RestClient.builder()
                .baseUrl(OPENAI_URL)
                .defaultHeader("Authorization", "Bearer " + openAiApiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> payload = buildRequestPayload(normalizedInput);
        String responseBody;
        try {
            responseBody = client.post()
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            log.error("OpenAI request failed for dish {}. status={} body={}", normalizedInput, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new IllegalStateException("Chiamata OpenAI fallita", ex);
        }

        return parseResponse(responseBody);
    }

    private Map<String, Object> buildRequestPayload(String normalizedInput) {
        Map<String, Object> responseFormat = new LinkedHashMap<String, Object>();
        responseFormat.put("type", "json_object");

        Map<String, Object> systemMessage = new LinkedHashMap<String, Object>();
        systemMessage.put("role", "system");
        systemMessage.put("content",
                "Sei un servizio di normalizzazione menu per ristoranti. " +
                "Rispondi solo con JSON valido, senza testo extra. " +
                "Formato richiesto: {\"nomeCanonico\": string, \"categoria\": string, \"ingredienti\": [{\"nome\": string, \"categoria\": string|null, \"grammi\": number|null}]}. " +
                "Normalizza il nome del piatto in italiano. Usa categoria breve minuscola come: antipasto, primo, secondo, contorno, dolce, bevanda, altro. " +
                "Se non conosci con certezza gli ingredienti, restituisci un array vuoto.");

        Map<String, Object> userMessage = new LinkedHashMap<String, Object>();
        userMessage.put("role", "user");
        userMessage.put("content", "Normalizza questo piatto ed estrai ingredienti strutturati: " + normalizedInput);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", openAiModel);
        payload.put("response_format", responseFormat);
        payload.put("messages", List.of(systemMessage, userMessage));
        return payload;
    }

    private LLMResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = extractContent(contentNode);
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("Risposta OpenAI priva di contenuto utile");
            }
            return objectMapper.readValue(content, LLMResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Unable to parse OpenAI response body: {}", responseBody, e);
            throw new RuntimeException("Impossibile parsare la risposta OpenAI", e);
        }
    }

    private String extractContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return null;
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode node : contentNode) {
                JsonNode textNode = node.path("text");
                if (!textNode.isMissingNode() && !textNode.isNull()) {
                    builder.append(textNode.asText());
                }
            }
            return builder.toString();
        }
        return contentNode.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
