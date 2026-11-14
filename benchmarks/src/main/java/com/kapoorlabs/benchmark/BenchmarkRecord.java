package com.kapoorlabs.benchmark;

import com.kapoorlabs.kiara.domain.annotations.*;
import lombok.Data;

@Data
public class BenchmarkRecord {
    private String id;
    private String category;
    private String status;
    private String region;
    private Tier tier;
    public enum Tier { BASIC, PLUS, PRO }
    @CaseInsensitive
    private String brand;
    private Integer quantity;
    private Double price;
    private Long serial;
    private Boolean active;
    private String optional;
    @CommaSeperatedStrings
    private String tags;
    @CommaSeperatedNumbers
    private String numbers;
    @DateFormat("yyyy-MM-dd")
    private String date;
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private String timestamp;
    @CommaSeperatedDates("yyyy-MM-dd")
    private String dates;
    @CommaSeperatedDateTimes("yyyy-MM-dd HH:mm:ss")
    private String timestamps;
    @NumericRange
    private String band;
    @NumericRange(numericStartPos = 2)
    private String route;
    @CommaSeperatedNumericRanges
    private String bands;
    @DateRange("yyyy-MM-dd")
    private String window;
    @DateTimeRange("yyyy-MM-dd HH:mm:ss")
    private String timeWindow;
    @CommaSeperatedDateRanges("yyyy-MM-dd")
    private String windows;
    @CommaSeperatedDateTimeRanges("yyyy-MM-dd HH:mm:ss")
    private String timeWindows;
    @CaseInsensitive
    @CommaSeperatedStrings
    @OneEditAway
    @Predictable
    private String name;
    @CaseInsensitive
    @CommaSeperatedStrings
    @StemmedIndex
    private String keywords;
    @IgnoreIndex
    private String description;
}
