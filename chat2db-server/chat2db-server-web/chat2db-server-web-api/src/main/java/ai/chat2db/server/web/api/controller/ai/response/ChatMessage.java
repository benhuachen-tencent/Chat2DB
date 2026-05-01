package ai.chat2db.server.web.api.controller.ai.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A nullable-content message DTO for deserializing streaming responses
 * from OpenAI-compatible APIs (e.g., DeepSeek) where content can be null.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String role;
    private String content;
}
