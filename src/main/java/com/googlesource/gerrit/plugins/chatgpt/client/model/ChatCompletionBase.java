package com.googlesource.gerrit.plugins.chatgpt.client.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;

@Data
public class ChatCompletionBase {
    protected String id;
    protected String object;
    protected long created;
    protected String model;


    @Data
    public static class Choice {
        protected Delta delta;
        protected int index;
        @SerializedName("finish_reason")
        protected String finishReason;
    }

    @Data
    public static class Delta {
        private String role;
        private List<ToolCall> tool_calls;
    }

    @Data
    public static class ToolCall {
        private String id;
        private String type;
        private Function function;

        @Data
        public static class Function {
            private String name;
            private String arguments;
        }
    }
}
