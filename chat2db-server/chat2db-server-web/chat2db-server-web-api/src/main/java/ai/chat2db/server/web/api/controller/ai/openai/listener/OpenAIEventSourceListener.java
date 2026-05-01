package ai.chat2db.server.web.api.controller.ai.openai.listener;

import java.util.Objects;

import ai.chat2db.server.web.api.controller.ai.response.ChatChoice;
import ai.chat2db.server.web.api.controller.ai.response.ChatCompletionResponse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfbx.chatgpt.entity.chat.Message;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * description：OpenAIEventSourceListener
 *
 * @author https:www.unfbx.com
 * @date 2023-02-22
 */
@Slf4j
public class OpenAIEventSourceListener extends EventSourceListener {

    private SseEmitter sseEmitter;

    /**
     * Buffer to accumulate streamed content for post-processing.
     * DeepSeek models may output reasoning text and markdown code blocks in the content field,
     * and markdown markers like ```sql can be split across chunks.
     * We buffer all content and send the cleaned result at the end.
     */
    private final StringBuilder contentBuffer = new StringBuilder();
    private String completionId;

    public OpenAIEventSourceListener(SseEmitter sseEmitter) {
        this.sseEmitter = sseEmitter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(EventSource eventSource, Response response) {
        log.info("[V3-BUFFER-CLEAN] OpenAI建立sse连接... (new version with buffer+clean active)");
    }

    /**
     * {@inheritDoc}
     */
    @SneakyThrows
    @Override
    public void onEvent(EventSource eventSource, String id, String type, String data) {
        log.info("[V3-BUFFER-CLEAN] OpenAI returns data: {}", data);
        if (data.equals("[DONE]")) {
            log.info("[V3-BUFFER-CLEAN] OpenAI returns data ended");
            // Process buffered content: clean up reasoning text and markdown code blocks
            String fullContent = contentBuffer.toString();
            log.info("[V3-BUFFER-CLEAN] full buffered content (len={}): {}", fullContent.length(), fullContent);
            String cleaned = cleanSqlOutput(fullContent);
            log.info("[V3-BUFFER-CLEAN] cleaned output (len={}): {}", cleaned.length(), cleaned);
            // Prepend a blank line so multiple outputs are visually separated in the editor
            String finalOutput = "\n" + cleaned;
            Message message = new Message();
            message.setContent(finalOutput);
            sseEmitter.send(SseEmitter.event()
                .id(completionId != null ? completionId : "[DONE]")
                .data(message)
                .reconnectTime(3000));
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]")
                .reconnectTime(3000));
            sseEmitter.complete();
            return;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Read JSON
        ChatCompletionResponse completionResponse = mapper.readValue(data, ChatCompletionResponse.class);
        ChatChoice choice = completionResponse.getChoices().get(0);
        String text = choice.getDelta() == null
            ? choice.getText()
            : choice.getDelta().getContent();
        if (completionResponse.getId() != null) {
            completionId = completionResponse.getId();
        }
        // Buffer content for post-processing instead of sending immediately
        if (text != null) {
            contentBuffer.append(text);
            log.info("[V3-BUFFER-CLEAN] buffered chunk (total len now={}): '{}'", contentBuffer.length(), text);
        }
    }

    /**
     * Clean up model output to extract only the SQL statement.
     * Handles:
     * 1. Markdown code blocks (```sql ... ```)
     * 2. Reasoning/thinking text before the actual SQL
     * 3. Extra explanations after the SQL
     */
    private String cleanSqlOutput(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        // Strategy 1: If content contains markdown code blocks, extract content from the LAST code block
        // (the last one is most likely the correct SQL)
        String extracted = content;
        if (content.contains("```")) {
            // Find the last ```...``` block
            int lastOpenIdx = content.lastIndexOf("```");
            // Search backwards for the matching opening ```
            String beforeLast = content.substring(0, lastOpenIdx);
            int matchingOpenIdx = beforeLast.lastIndexOf("```");
            if (matchingOpenIdx >= 0) {
                extracted = content.substring(matchingOpenIdx, lastOpenIdx);
                // Remove the opening ``` and optional language tag (e.g. ```sql)
                extracted = extracted.replaceFirst("```\\w*\\s*", "");
            }
        }

        // Strategy 2: Remove any remaining ``` markers
        extracted = extracted.replaceAll("```\\w*", "").replaceAll("```", "");

        // Strategy 3: Try to find actual SQL statements and discard reasoning text before them
        // Look for common SQL keywords at the start of a line
        String[] lines = extracted.split("\n");
        StringBuilder sqlBuilder = new StringBuilder();
        boolean foundSql = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!foundSql) {
                // Check if this line looks like SQL (starts with SQL keyword or SQL comment)
                if (trimmed.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|WITH|EXPLAIN|SHOW|DESCRIBE|USE|--.*)\\b.*")) {
                    foundSql = true;
                    sqlBuilder.append(line).append("\n");
                }
            } else {
                // Once we've found SQL, keep appending until we hit a non-SQL line or end
                if (trimmed.isEmpty() || trimmed.matches("(?i)^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|WITH|EXPLAIN|SHOW|DESCRIBE|USE|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|ON|AND|OR|GROUP|ORDER|HAVING|LIMIT|OFFSET|UNION|SET|VALUES|INTO|--.*)\\b.*")
                    || trimmed.endsWith(";") || trimmed.startsWith("(") || trimmed.startsWith(")")) {
                    sqlBuilder.append(line).append("\n");
                } else {
                    // Stop at non-SQL text (explanations after SQL)
                    break;
                }
            }
        }

        String result = foundSql ? sqlBuilder.toString().trim() : extracted.trim();
        return result;
    }

    @Override
    public void onClosed(EventSource eventSource) {
        sseEmitter.complete();
        log.info("OpenAI closes sse connection...");
    }

    @Override
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
        try {
            if (Objects.isNull(response)) {
                String message = t.getMessage();
                if ("No route to host".equals(message)) {
                    message = "The network connection timed out. Please Baidu solve the network problem by yourself.";
                }
                Message sseMessage = new Message();
                sseMessage.setContent(message);
                sseEmitter.send(SseEmitter.event()
                    .id("[ERROR]")
                    .data(sseMessage));
                sseEmitter.send(SseEmitter.event()
                    .id("[DONE]")
                    .data("[DONE]"));
                sseEmitter.complete();
                return;
            }
            ResponseBody body = response.body();
            String bodyString = null;
            if (Objects.nonNull(body)) {
                bodyString = body.string();
                log.error("OpenAI sse connection exception data: {}", bodyString, t);
            } else {
                log.error("OpenAI sse connection exception data: {}", response, t);
            }
            eventSource.cancel();
            Message message = new Message();
            message.setContent("An exception occurred, please view the detailed log in the help：" + bodyString);
            sseEmitter.send(SseEmitter.event()
                .id("[ERROR]")
                .data(message));
            sseEmitter.send(SseEmitter.event()
                .id("[DONE]")
                .data("[DONE]"));
            sseEmitter.complete();
        } catch (Exception exception) {
            log.error("Exception in sending data:", exception);
        }
    }
}
