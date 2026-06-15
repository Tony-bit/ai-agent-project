package denny.ai.agent.domain.service.auto.step.routing;

import denny.ai.agent.domain.model.valobj.BaseSlot;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskIntentRoutingOutput {

    private String intent;
    private String confidence;
    private String reasoning;
    private BaseSlot baseSlot;
    private Map<String, Object> intentSpecificSlots;
}
