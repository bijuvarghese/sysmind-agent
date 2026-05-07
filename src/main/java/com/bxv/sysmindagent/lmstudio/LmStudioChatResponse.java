package com.bxv.sysmindagent.lmstudio;

import java.util.List;

public record LmStudioChatResponse(
        List<LmStudioChoice> choices
) {
}
