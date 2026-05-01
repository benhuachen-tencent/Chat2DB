package ai.chat2db.server.web.api.controller.ai.response;

import java.io.Serial;
import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Custom Delta class that allows null content.
 * This is needed because DeepSeek API returns content=null in the first chunk
 * when using reasoning/thinking mode (it sends reasoning_content instead).
 * The third-party library's Message class uses @NonNull on content, which causes
 * deserialization errors.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatDelta implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String role;

    private String content;

    @JsonProperty("reasoning_content")
    private String reasoningContent;
}
