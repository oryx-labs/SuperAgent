package com.superagent.core.media;

import java.util.List;

/**
 * Which models can do what, told from what was checked, never configured by hand: speech to text (probed with the
 * model check) and text (passed the inference check and allowed for tasks). Implemented by the cost module.
 */
public interface ModelCapabilities {

    /** Every speech model that answered its probe, timed ones first. */
    List<SpeechModel> transcribers();

    /** Every text model tasks may run on, cheapest first. */
    List<TextModel> textModels();

    /**
     * The models that correct a subtitle's disputed spans: the strongest of each vendor, strongest first, at most the
     * company's limit; within the fixed models while tasks are held to those.
     */
    List<TextModel> calibrators();

    record TextModel(String model, String cli, String vendor) {
    }
}
