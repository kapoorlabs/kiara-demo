package com.kapoorlabs.benchmark;

import java.util.*;

final class ExecutionStatistics {
    private ExecutionStatistics() {}

    static double median(List<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("No measurements");
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
    }

    static double ratio(List<double[]> kiara, List<double[]> other) {
        if (kiara.isEmpty() || other.isEmpty() || kiara.getFirst().length == 0) {
            throw new IllegalArgumentException("No equivalent measurements");
        }
        int count = kiara.getFirst().length;
        if (java.util.stream.Stream.concat(kiara.stream(), other.stream()).anyMatch(row -> row.length != count
                || Arrays.stream(row).anyMatch(value -> !Double.isFinite(value) || value <= 0))) {
            throw new IllegalArgumentException("Invalid measurement matrix");
        }
        double total = 0;
        for (int column = 0; column < count; column++) {
            int c = column;
            total += Math.log(median(other.stream().map(row -> row[c]).toList())
                    / median(kiara.stream().map(row -> row[c]).toList()));
        }
        return Math.exp(total / count);
    }

    static double[] bootstrap(List<double[]> kiara, List<double[]> other) {
        Random random = new Random(20261028L);
        double[] values = new double[5000];
        for (int i = 0; i < values.length; i++) {
            List<double[]> k = new ArrayList<>();
            List<double[]> o = new ArrayList<>();
            for (int j = 0; j < kiara.size(); j++) k.add(kiara.get(random.nextInt(kiara.size())));
            for (int j = 0; j < other.size(); j++) o.add(other.get(random.nextInt(other.size())));
            values[i] = ratio(k, o);
        }
        Arrays.sort(values);
        return new double[]{values[(int) Math.ceil(values.length * .025) - 1],
                values[(int) Math.ceil(values.length * .975) - 1]};
    }
}
