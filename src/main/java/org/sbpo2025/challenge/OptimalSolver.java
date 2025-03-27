package org.sbpo2025.challenge;

import java.util.*;
import java.util.concurrent.*;

public class OptimalSolver extends ChallengeSolver {
    private final int NUM_THREADS = Runtime.getRuntime().availableProcessors();

    public OptimalSolver(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        super(orders, aisles, nItems, waveSizeLB, waveSizeUB);
    }
    public Individual findOptimalSolution() {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<Individual>> futures = new ArrayList<>();

        System.out.println("Iniciando busca ótima com " + NUM_THREADS + " threads.");

        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i; // Capturar ID da thread
            futures.add(executor.submit(() -> solveOptimally(threadId)));
        }

        Individual bestSolution = null;
        try {
            for (Future<Individual> future : futures) {
                Individual candidate = future.get();
                if (bestSolution == null || (candidate != null && candidate.fitness > bestSolution.fitness)) {
                    bestSolution = candidate;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Erro na busca ótima: " + e.getMessage());
            e.printStackTrace();
        }

        executor.shutdown();
        return bestSolution;
    }

    private Individual solveOptimally(int threadId) {
        System.out.println("Thread " + threadId + " iniciou busca exaustiva.");

        ArrayList<Boolean> bestGenome = new ArrayList<>(Collections.nCopies(orders.size() + aisles.size(), false));
        double bestFitness = Double.NEGATIVE_INFINITY;

        try {
            for (int i = 0; i < (1 << orders.size()); i++) {
                ArrayList<Boolean> candidateGenome = new ArrayList<>(Collections.nCopies(orders.size() + aisles.size(), false));
                int totalUnits = 0;
                Set<Integer> selectedOrders = new HashSet<>();

                for (int j = 0; j < orders.size(); j++) {
                    if ((i & (1 << j)) != 0) {
                        int orderUnits = orders.get(j).values().stream().mapToInt(Integer::intValue).sum();
                        if (totalUnits + orderUnits <= waveSizeUB) {
                            candidateGenome.set(j, true);
                            selectedOrders.add(j);
                            totalUnits += orderUnits;
                        }
                    }
                }

                if (totalUnits >= waveSizeLB) {
                    Set<Integer> selectedAisles = getAisles(selectedOrders);
                    for (int aisleIndex : selectedAisles) {
                        candidateGenome.set(orders.size() + aisleIndex, true);
                    }

                    Individual candidate = new Individual(candidateGenome, 0.0);
                    double fitness = evaluate(candidate);

                    if (fitness > bestFitness) {
                        bestGenome = new ArrayList<>(candidateGenome);
                        bestFitness = fitness;
                        System.out.println("Thread " + threadId + " encontrou nova melhor solução: Fitness = " + bestFitness);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Erro na thread " + threadId + ": " + e.getMessage());
            e.printStackTrace();
        }

        return new Individual(bestGenome, bestFitness);
    }

    private Set<Integer> getAisles(Set<Integer> selectedOrders) {
        Set<Integer> selectedAisles = new HashSet<>();
        for (int orderIndex : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(orderIndex).entrySet()) {
                int item = entry.getKey();
                for (int aisleIndex = 0; aisleIndex < aisles.size(); aisleIndex++) {
                    if (aisles.get(aisleIndex).containsKey(item)) {
                        selectedAisles.add(aisleIndex);
                    }
                }
            }
        }
        return selectedAisles;
    }

    private double evaluate(Individual individual) {
        ChallengeSolution solution = decodeIndividual(individual);

        if (solution == null || solution.orders() == null || solution.aisles() == null) {
            return 0.0;
        }

        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();

        if (selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }

        int penalty = 0;
        if (!isSolutionFeasible(solution)) {
            penalty -= applyPenalty(solution);
        }

        int totalUnitsPicked = selectedOrders.stream()
                .mapToInt(order -> orders.get(order).values().stream().mapToInt(Integer::intValue).sum())
                .sum();

        int numVisitedAisles = visitedAisles.size();

        return (double) totalUnitsPicked / numVisitedAisles + penalty;
    }
}