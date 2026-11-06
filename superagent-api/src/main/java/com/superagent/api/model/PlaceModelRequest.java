package com.superagent.api.model;

/** Puts a model in a tier by hand; a blank {@code tier} goes back to automatic. */
public record PlaceModelRequest(String modelId, String tier) {
}
