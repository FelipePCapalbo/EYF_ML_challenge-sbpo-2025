package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.HashSet;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;

    public ChallengeSolution solve(StopWatch stopWatch) {
        // --- Algoritmo Greedy para seleção de pedidos (wave) e corredores (aisles) ---

        // Passo 1: Calcular a quantidade total de unidades de cada pedido
        // e montar uma lista (pedido, quantidadeTotal).
        List<int[]> ordersInfo = new ArrayList<>();
        for (int orderIndex = 0; orderIndex < orders.size(); orderIndex++) {
            int totalUnitsOrder = orders.get(orderIndex)
                                        .values()
                                        .stream()
                                        .mapToInt(Integer::intValue)
                                        .sum();
            ordersInfo.add(new int[]{orderIndex, totalUnitsOrder});
        }

        // Passo 2: Ordenar os pedidos em ordem decrescente de totalUnitsOrder
        // (tentamos primeiro pegar os "maiores" pedidos em termos de unidades).
        ordersInfo.sort((a, b) -> Integer.compare(b[1], a[1]));

        // Conjuntos que vão compor a solução
        Set<Integer> selectedOrders = new HashSet<>();
        Set<Integer> visitedAisles = new HashSet<>();

        // Passo 3: Selecionar pedidos de forma greedy respeitando os limites LB e UB
        int currentUnits = 0;
        for (int[] info : ordersInfo) {
            int candidateOrderIndex = info[0];
            int candidateOrderUnits = info[1];

            // Se ao incluir este pedido excederíamos o UB, não incluímos
            if (currentUnits + candidateOrderUnits > waveSizeUB) {
                continue;
            }

            // Caso contrário, podemos incluir esse pedido na wave
            selectedOrders.add(candidateOrderIndex);
            currentUnits += candidateOrderUnits;

            // Se já atingimos ou passamos do LB, podemos parar
            if (currentUnits >= waveSizeLB) {
                break;
            }
        }

        // Verificação rápida: se não conseguimos atingir o LB, pegamos de todo modo
        // o que selecionamos, mas a solução pode acabar inviável se currentUnits < LB.
        // (a verificação final de viabilidade já é feita em isSolutionFeasible()).

        // Passo 4: Para garantir a viabilidade de forma simples, selecionamos "todos" corredores.
        // (Uma heurística mais elaborada poderia tentar escolher menos corredores;
        //  aqui fazemos o mais simples: usar todos e garantir suprimento.)
        for (int aisleIndex = 0; aisleIndex < aisles.size(); aisleIndex++) {
            visitedAisles.add(aisleIndex);
        }

        // Retornamos a solução com o conjunto de pedidos e corredores
        return new ChallengeSolution(selectedOrders, visitedAisles);
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
