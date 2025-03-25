package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Classe Solver com implementação de heurística "Relax and Fix" (exemplo ilustrativo).
 * Nota: Em uma competição real, seria necessário integrar efetivamente com
 *       um solver (ex: cplex) para resolver a formulação relaxada e realizar
 *       o procedimento de fixar variáveis progressivamente.
 */
public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // 10 minutos em ms

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders,
            List<Map<Integer, Integer>> aisles,
            int nItems,
            int waveSizeLB,
            int waveSizeUB
    ) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        // =============================================================
        // Estratégia: Tentar múltiplos embaralhamentos para formar uma
        // wave factível (soma total entre LB e UB) e, então, selecionar
        // corredores que cubram a demanda de cada item.
        // Se nenhuma tentativa randomizada produzir solução, usa fallback.
        // =============================================================
        if (stopWatch.getTime(TimeUnit.MILLISECONDS) >= MAX_RUNTIME) {
            return null;
        }
        
        // Filtra os pedidos que individualmente não ultrapassam o limite superior.
        List<Integer> candidateIndices = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            int orderSum = orders.get(i).values().stream().mapToInt(Integer::intValue).sum();
            if (orderSum <= waveSizeUB) {
                candidateIndices.add(i);
            }
        }
        if (candidateIndices.isEmpty()) {
            // Se nenhum pedido é viável individualmente, a instância é inviável.
            return null;
        }
        
        ChallengeSolution bestSolution = null;
        double bestObj = -Double.MAX_VALUE;
        int trials = 200; // Número de tentativas randomizadas
        Random rand = new Random();
        
        // Tentativas randomizadas para seleção dos pedidos
        for (int t = 0; t < trials && stopWatch.getTime(TimeUnit.MILLISECONDS) < MAX_RUNTIME; t++) {
            List<Integer> perm = new ArrayList<>(candidateIndices);
            Collections.shuffle(perm, rand);
            List<Integer> selectedOrders = new ArrayList<>();
            int totalUnits = 0;
            // Adiciona pedidos de forma gulosa (sem ultrapassar UB)
            for (int idx : perm) {
                int orderUnits = orders.get(idx).values().stream().mapToInt(Integer::intValue).sum();
                if (totalUnits + orderUnits <= waveSizeUB) {
                    selectedOrders.add(idx);
                    totalUnits += orderUnits;
                }
            }
            if (totalUnits < waveSizeLB) {
                // Não atingiu o limite inferior; descarta esta tentativa.
                continue;
            }
            
            // Calcula a demanda total para cada item dos pedidos selecionados.
            int[] demand = new int[nItems];
            for (Integer order : selectedOrders) {
                for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                    int item = entry.getKey();
                    int qty = entry.getValue();
                    demand[item] += qty;
                }
            }
            
            // Seleção dos corredores usando procedimento guloso (set cover)
            int[] covered = new int[nItems]; // Cobertura atual para cada item
            Set<Integer> selectedAisles = new HashSet<>();
            while (true) {
                if (stopWatch.getTime(TimeUnit.MILLISECONDS) >= MAX_RUNTIME) {
                    return null;
                }
                boolean allCovered = true;
                for (int i = 0; i < nItems; i++) {
                    if (covered[i] < demand[i]) {
                        allCovered = false;
                        break;
                    }
                }
                if (allCovered) {
                    break;
                }
                int bestAisle = -1;
                int bestCoverage = 0;
                // Avalia cada corredor não selecionado para cobrir a demanda remanescente
                for (int a = 0; a < aisles.size(); a++) {
                    if (selectedAisles.contains(a)) {
                        continue;
                    }
                    int additionalCoverage = 0;
                    for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                        int item = entry.getKey();
                        int supply = entry.getValue();
                        int remaining = demand[item] - covered[item];
                        if (remaining > 0) {
                            additionalCoverage += Math.min(supply, remaining);
                        }
                    }
                    if (additionalCoverage > bestCoverage) {
                        bestCoverage = additionalCoverage;
                        bestAisle = a;
                    }
                }
                if (bestAisle == -1) {
                    // Não há corredor que possa cobrir a demanda remanescente.
                    break;
                }
                selectedAisles.add(bestAisle);
                // Atualiza a cobertura com os itens do corredor selecionado.
                for (Map.Entry<Integer, Integer> entry : aisles.get(bestAisle).entrySet()) {
                    int item = entry.getKey();
                    int supply = entry.getValue();
                    covered[item] += supply;
                }
            }
            // Verifica se todos os itens estão cobertos.
            boolean feasibleCandidate = true;
            for (int i = 0; i < nItems; i++) {
                if (covered[i] < demand[i]) {
                    feasibleCandidate = false;
                    break;
                }
            }
            if (!feasibleCandidate) {
                continue;
            }
            // Cria a solução candidata.
            Set<Integer> ordersSet = new HashSet<>(selectedOrders);
            ChallengeSolution candidateSolution = new ChallengeSolution(ordersSet, selectedAisles);
            double objVal = (double) totalUnits / selectedAisles.size();
            if (objVal > bestObj) {
                bestObj = objVal;
                bestSolution = candidateSolution;
            }
        }
        
        // Se uma solução foi encontrada nas tentativas randomizadas, a retorna.
        if (bestSolution != null && isSolutionFeasible(bestSolution)) {
            return bestSolution;
        }
        
        // Fallback determinístico: seleção de pedidos em ordem crescente.
        List<Integer> orderIndices = new ArrayList<>(candidateIndices);
        orderIndices.sort(Comparator.comparingInt((Integer i) ->
                orders.get(i).values().stream().mapToInt(Integer::intValue).sum()));
        List<Integer> selectedOrders = new ArrayList<>();
        int totalUnits = 0;
        for (Integer i : orderIndices) {
            if (stopWatch.getTime(TimeUnit.MILLISECONDS) >= MAX_RUNTIME) {
                return null;
            }
            int orderUnits = orders.get(i).values().stream().mapToInt(Integer::intValue).sum();
            if (totalUnits + orderUnits <= waveSizeUB) {
                selectedOrders.add(i);
                totalUnits += orderUnits;
            }
        }
        if (totalUnits < waveSizeLB) {
            return null;
        }
        int[] demand = new int[nItems];
        for (Integer order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                int item = entry.getKey();
                int qty = entry.getValue();
                demand[item] += qty;
            }
        }
        int[] covered = new int[nItems];
        Set<Integer> selectedAisles = new HashSet<>();
        while (true) {
            boolean allCovered = true;
            for (int i = 0; i < nItems; i++) {
                if (covered[i] < demand[i]) {
                    allCovered = false;
                    break;
                }
            }
            if (allCovered) {
                break;
            }
            int bestAisle = -1;
            int bestCoverage = 0;
            for (int a = 0; a < aisles.size(); a++) {
                if (selectedAisles.contains(a)) {
                    continue;
                }
                int additionalCoverage = 0;
                for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                    int item = entry.getKey();
                    int supply = entry.getValue();
                    int remaining = demand[item] - covered[item];
                    if (remaining > 0) {
                        additionalCoverage += Math.min(supply, remaining);
                    }
                }
                if (additionalCoverage > bestCoverage) {
                    bestCoverage = additionalCoverage;
                    bestAisle = a;
                }
            }
            if (bestAisle == -1) {
                break;
            }
            selectedAisles.add(bestAisle);
            for (Map.Entry<Integer, Integer> entry : aisles.get(bestAisle).entrySet()) {
                int item = entry.getKey();
                int supply = entry.getValue();
                covered[item] += supply;
            }
        }
        Set<Integer> ordersSet = new HashSet<>(selectedOrders);
        ChallengeSolution fallbackSolution = new ChallengeSolution(ordersSet, selectedAisles);
        return isSolutionFeasible(fallbackSolution) ? fallbackSolution : null;
    }

    /**
     * Retorna o tempo restante em segundos.
     */
    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0
        );
    }

    /**
     * Verifica a viabilidade de uma solução candidata.
     */
    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        // Calcula o total de unidades demandadas pelos pedidos selecionados.
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calcula o total de unidades disponíveis nos corredores selecionados.
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

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
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }
        int numVisitedAisles = visitedAisles.size();
        return (double) totalUnitsPicked / numVisitedAisles;
    }
}
