package com.platform.counter.dto;

/**
 * Result of a like / fav / view / share action.
 *
 * <p>{@code changed=true} means the action caused a real state transition (a new increment or
 * decrement event was emitted to Kafka). For like/fav it is {@code true} only on the first like/fav
 * by a given user (bitmap transition); for view/share every action emits and thus always reports
 * {@code true}.
 */
public record InteractionResponse(boolean changed) {}
