package com.pbm.price.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AliExpressNormalizer {
    public String normalize(String firstLevelCategoryName, String secondLevelCategoryName){
        List<String> segments = new ArrayList<>();

        addIfPresent(segments, firstLevelCategoryName);
        addIfPresent(segments, secondLevelCategoryName);

        if (segments.isEmpty()){
            return null;
        }

        return String.join("/", segments);
    }

    private void addIfPresent(List<String> segments, String value){
        String normalized = normalizeSegment(value);
        if (normalized != null){
            segments.add(normalized);
        }
    }

    private String normalizeSegment(String value){
        if (value == null || value.isBlank()){
            return null;
        }

        return value.trim()
                .replace("/", "")
                .replaceAll("\\s+", "-");
    }
}
