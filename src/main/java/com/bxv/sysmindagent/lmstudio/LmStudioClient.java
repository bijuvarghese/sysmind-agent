package com.bxv.sysmindagent.lmstudio;

import java.util.List;
import reactor.core.publisher.Mono;

public interface LmStudioClient {

    Mono<String> complete(List<LmStudioMessage> messages);
}
