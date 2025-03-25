package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 100; // milliseconds; 10 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    private int[][] orderQuantities;
    private int[][] aisleQuantities;
    private int[] orderTotalItems;

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
        initializeQuantities();
    }

    /** Inicializa as estruturas de dados para acesso eficiente */
    private void initializeQuantities() {
        orderQuantities = new int[orders.size()][nItems];
        for (int o = 0; o < orders.size(); o++) {
            for (Map.Entry<Integer, Integer> entry : orders.get(o).entrySet()) {
                int i = entry.getKey();
                int qty = entry.getValue();
                orderQuantities[o][i] = qty;
            }
        }
        aisleQuantities = new int[aisles.size()][nItems];
        for (int a = 0; a < aisles.size(); a++) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                int i = entry.getKey();
                int qty = entry.getValue();
                aisleQuantities[a][i] = qty;
            }
        }
        orderTotalItems = new int[orders.size()];
        for (int o = 0; o < orders.size(); o++) {
            for (int i = 0; i < nItems; i++) {
                orderTotalItems[o] += orderQuantities[o][i];
            }
        }
    }

    /** Heurística gulosa para resolver o problema */
    public ChallengeSolution solve(StopWatch stopWatch) {
        Set<Integer> O = new HashSet<>(); // Conjunto de pedidos selecionados
        Set<Integer> A = new HashSet<>(); // Conjunto de corredores selecionados
        int[] currentRequirement = new int[nItems]; // Requisitos atuais por item
        int totalItems = 0; // Total de itens coletados
        int[] available = new int[nItems]; // Quantidades disponíveis nos corredores selecionados

        while (totalItems < waveSizeLB && getRemainingTime(stopWatch) > 0) {
            // Encontrar pedidos candidatos que não excedam UB
            List<Integer> candidates = new ArrayList<>();
            for (int o = 0; o < orders.size(); o++) {
                if (!O.contains(o) && totalItems + orderTotalItems[o] <= waveSizeUB) {
                    candidates.add(o);
                }
            }
            if (candidates.isEmpty()) {
                break; // Não há mais pedidos viáveis
            }

            // Encontrar pedidos que podem ser adicionados sem novos corredores
            List<Integer> noNewAisles = new ArrayList<>();
            for (int o : candidates) {
                boolean canAdd = true;
                for (int i = 0; i < nItems; i++) {
                    if (currentRequirement[i] + orderQuantities[o][i] > available[i]) {
                        canAdd = false;
                        break;
                    }
                }
                if (canAdd) {
                    noNewAisles.add(o);
                }
            }

            if (!noNewAisles.isEmpty()) {
                // Selecionar o pedido com maior número de itens
                int bestO = noNewAisles.stream()
                        .max(Comparator.comparingInt(o -> orderTotalItems[o]))
                        .get();
                O.add(bestO);
                for (int i = 0; i < nItems; i++) {
                    currentRequirement[i] += orderQuantities[bestO][i];
                }
                totalItems += orderTotalItems[bestO];
            } else {
                // Selecionar o pedido com melhor razão itens/requisitos adicionais
                double bestRatio = -1;
                int bestO = -1;
                for (int o : candidates) {
                    int sumRemainingReq = 0;
                    for (int i = 0; i < nItems; i++) {
                        sumRemainingReq += Math.max(0, currentRequirement[i] + orderQuantities[o][i] - available[i]);
                    }
                    double ratio = (double) orderTotalItems[o] / (sumRemainingReq + 1);
                    if (ratio > bestRatio) {
                        bestRatio = ratio;
                        bestO = o;
                    }
                }
                if (bestO == -1) {
                    break; // Nenhum pedido pode ser adicionado
                }

                // Adicionar o pedido selecionado
                O.add(bestO);
                for (int i = 0; i < nItems; i++) {
                    currentRequirement[i] += orderQuantities[bestO][i];
                }
                totalItems += orderTotalItems[bestO];

                // Selecionar corredores adicionais necessários
                int[] remainingReq = new int[nItems];
                for (int i = 0; i < nItems; i++) {
                    remainingReq[i] = Math.max(0, currentRequirement[i] - available[i]);
                }
                Set<Integer> additionalAisles = greedyAisleSelection(remainingReq);
                for (int a : additionalAisles) {
                    A.add(a);
                    for (int i = 0; i < nItems; i++) {
                        available[i] += aisleQuantities[a][i];
                    }
                }
            }
        }

        return new ChallengeSolution(O, A);
    }

    /** Seleção gulosa de corredores para atender aos requisitos restantes */
    private Set<Integer> greedyAisleSelection(int[] required) {
        Set<Integer> selected = new HashSet<>();
        int[] remaining = required.clone();
        while (true) {
            int bestA = -1;
            int bestScore = 0;
            for (int a = 0; a < aisles.size(); a++) {
                if (selected.contains(a)) continue;
                int score = 0;
                for (int i = 0; i < nItems; i++) {
                    if (remaining[i] > 0) {
                        score += Math.min(remaining[i], aisleQuantities[a][i]);
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestA = a;
                }
            }
            if (bestScore == 0) break; // Nenhum corredor adicional ajuda
            selected.add(bestA);
            for (int i = 0; i < nItems; i++) {
                remaining[i] = Math.max(0, remaining[i] - aisleQuantities[bestA][i]);
            }
        }
        return selected;
    }

    /*
     * Get the remaining time in seconds
     */
    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        // Calculate total units picked
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calculate total units available
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        // Check if the total units picked are within bounds
        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }

    protected double computeObjectiveFunction(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        // Calculate total units picked
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        // Calculate the number of visited aisles
        int numVisitedAisles = visitedAisles.size();

        // Objective function: total units picked / number of visited aisles
        return (double) totalUnitsPicked / numVisitedAisles;
    }
}