package com.github.curiousoddman.curious_images.model;

import java.util.List;

/**
 * All data needed to build the timeline subtree in one DB round-trip.
 *
 * @param days         every distinct (year, month, day) that has at least one photo, with counts
 * @param undatedCount number of photos whose {@code capture_date} is NULL
 */
public record TimelineData(List<TimelineDay> days, int undatedCount) {

    public record TimelineDay(int year, int month, int day, int count) {
    }
}