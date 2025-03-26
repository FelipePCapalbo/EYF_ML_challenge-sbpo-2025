package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

public class ChallengeSolver {
    private static final Logger LOGGER = Logger.getLogger(ChallengeSolver.class.getName());
    private final long MAX_RUNTIME = 600000; // 10 minutos em milissegundos
    protected List<Map<Integer, Integer>> orders; // Lista de pedidos (item -> quantidade)
    protected List<Map<Integer, Integer>> aisles; // Lista de corredores (item -> quantidade)
    protected int nItems; // Número total de itens distintos
    protected int waveSizeLB; // Limite inferior de unidades por onda
    protected int waveSizeUB; // Limite superior de unidades por onda

    public ChallengeSolver(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, 
                           int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
        configureLogger();
    }

    private void configureLogger() {
        LOGGER.setLevel(Level.ALL);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter() {
            private static final String FORMAT = "[%1$tF %1$tT] [%2$-7s] %3$s %n";
            @Override
            public synchronized String format(LogRecord lr) {
                return String.format(FORMAT,
                        new Date(lr.getMillis()),
                        lr.getLevel().getLocalizedName(),
                        lr.getMessage());
            }
        });
        LOGGER.addHandler(handler);
        LOGGER.setUseParentHandlers(false);
    }

    /**
     * Resolve o problema, utilizando o tempo limite para encontrar a melhor solução possível.
     * @param stopWatch Cronômetro para controlar o tempo máximo de execução.
     * @return A melhor solução encontrada (viável ou parcial).
     */
    public ChallengeSolution solve(StopWatch stopWatch) {
        LOGGER.info("Iniciando processamento. LB=" + waveSizeLB + ", UB=" + waveSizeUB + ", nItems=" + nItems);

        ChallengeSolution bestSolution = null;
        double bestObjective = Double.NEGATIVE_INFINITY;
        List<List<Integer>> strategies = Arrays.asList(
            orderByUnitsDescending(), 
            orderByUnitsAscending(), 
            orderRandomly()
        );
        int iteration = 0;

        while (getRemainingTime(stopWatch) > 0) {
            iteration++;
            LOGGER.info("Iteração " + iteration + " começando.");

            // Escolhe uma estratégia de ordenação
            List<Integer> orderIndices = strategies.get(iteration % strategies.size());
            if (iteration % strategies.size() == 2) {
                orderIndices = orderRandomly(); // Nova ordem aleatória a cada ciclo
            }

            // Constrói uma solução candidata
            ChallengeSolution candidate = buildGreedySolution(orderIndices, stopWatch);
            if (candidate != null) {
                double objective = computeObjectiveFunction(candidate);
                boolean feasible = isSolutionFeasible(candidate);

                LOGGER.info("Solução candidata: Objetivo=" + objective + ", Viável=" + feasible);

                if (feasible && objective > bestObjective) {
                    bestSolution = candidate;
                    bestObjective = objective;
                    LOGGER.info("Nova melhor solução viável encontrada: Objetivo=" + bestObjective);
                } else if (bestSolution == null && objective > bestObjective) {
                    // Aceita uma solução parcial como melhor solução inicial
                    bestSolution = candidate;
                    bestObjective = objective;
                    LOGGER.info("Melhor solução parcial atualizada: Objetivo=" + bestObjective);
                }
            }

            // Pequena pausa para evitar uso excessivo de CPU
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupção durante pausa: " + e.getMessage());
            }
        }

        if (bestSolution == null) {
            LOGGER.warning("Nenhuma solução encontrada após " + iteration + " iterações.");
            bestSolution = buildPartialSolution(orderByUnitsAscending(), stopWatch);
            if (bestSolution != null) {
                LOGGER.info("Solução parcial gerada como fallback: Pedidos=" + bestSolution.orders());
            } else {
                LOGGER.severe("Falha ao gerar mesmo uma solução parcial.");
            }
        } else {
            LOGGER.info("Melhor solução final: Objetivo=" + bestObjective + 
                        ", Pedidos=" + bestSolution.orders() + 
                        ", Corredores=" + bestSolution.aisles());
        }

        return bestSolution;
    }

    /**
     * Constrói uma solução gulosa com base na ordem dos pedidos.
     */
    private ChallengeSolution buildGreedySolution(List<Integer> orderIndices, StopWatch stopWatch) {
        Set<Integer> selectedOrders = new HashSet<>();
        Set<Integer> selectedAisles = new HashSet<>();
        Map<Integer, Integer> availableUnits = new HashMap<>();
        int currentTotalUnits = 0;

        for (int orderIndex : orderIndices) {
            if (getRemainingTime(stopWatch) <= 0) break;

            Map<Integer, Integer> order = orders.get(orderIndex);
            int orderTotalUnits = order.values().stream().mapToInt(Integer::intValue).sum();

            if (currentTotalUnits + orderTotalUnits <= waveSizeUB && 
                isOrderFeasible(order, selectedAisles, availableUnits, orderTotalUnits)) {
                selectedOrders.add(orderIndex);
                currentTotalUnits += orderTotalUnits;
                LOGGER.fine("Pedido " + orderIndex + " adicionado. Total=" + currentTotalUnits);
            }
        }

        return new ChallengeSolution(selectedOrders, selectedAisles);
    }

    /**
     * Verifica se um pedido é viável e atualiza os corredores e unidades disponíveis.
     */
    private boolean isOrderFeasible(Map<Integer, Integer> order, Set<Integer> selectedAisles, 
                                    Map<Integer, Integer> availableUnits, int orderTotalUnits) {
        Map<Integer, Integer> tempUnits = new HashMap<>(availableUnits);
        Set<Integer> additionalAisles = new HashSet<>();

        for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
            int item = entry.getKey();
            int demand = entry.getValue();
            int available = tempUnits.getOrDefault(item, 0);
            if (demand > available) {
                Set<Integer> aislesForItem = findMinimalAislesForOrder(order, selectedAisles);
                if (aislesForItem == null) return false;
                additionalAisles.addAll(aislesForItem);
                for (int aisleIdx : aislesForItem) {
                    Map<Integer, Integer> aisle = aisles.get(aisleIdx);
                    for (Map.Entry<Integer, Integer> aisleEntry : aisle.entrySet()) {
                        tempUnits.merge(aisleEntry.getKey(), aisleEntry.getValue(), Integer::sum);
                    }
                }
                if (tempUnits.getOrDefault(item, 0) < demand) return false;
            }
            tempUnits.put(item, tempUnits.get(item) - demand);
        }

        selectedAisles.addAll(additionalAisles);
        availableUnits.clear();
        availableUnits.putAll(tempUnits);
        return true;
    }

    /**
     * Encontra corredores mínimos para suprir um pedido.
     */
    private Set<Integer> findMinimalAislesForOrder(Map<Integer, Integer> order, Set<Integer> currentAisles) {
        Map<Integer, Integer> demand = new HashMap<>(order);
        Set<Integer> selectedAisles = new HashSet<>();
        List<Integer> availableAisles = new ArrayList<>();
        for (int i = 0; i < aisles.size(); i++) {
            if (!currentAisles.contains(i)) availableAisles.add(i);
        }

        while (!demand.isEmpty() && !availableAisles.isEmpty()) {
            int bestAisle = -1;
            int maxCoverage = -1;
            for (int aisleIdx : availableAisles) {
                int coverage = computeCoverage(aisles.get(aisleIdx), demand);
                if (coverage > maxCoverage) {
                    maxCoverage = coverage;
                    bestAisle = aisleIdx;
                }
            }
            if (maxCoverage <= 0) return null;
            selectedAisles.add(bestAisle);
            availableAisles.remove(Integer.valueOf(bestAisle));
            Map<Integer, Integer> aisle = aisles.get(bestAisle);
            for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
                int item = entry.getKey();
                int supplied = entry.getValue();
                if (demand.containsKey(item)) {
                    int remaining = demand.get(item) - supplied;
                    if (remaining <= 0) demand.remove(item);
                    else demand.put(item, remaining);
                }
            }
        }
        return demand.isEmpty() ? selectedAisles : null;
    }

    private int computeCoverage(Map<Integer, Integer> aisle, Map<Integer, Integer> demand) {
        int coverage = 0;
        for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
            int item = entry.getKey();
            int available = entry.getValue();
            Integer needed = demand.get(item);
            if (needed != null) coverage += Math.min(available, needed);
        }
        return coverage;
    }

    /**
     * Constrói uma solução parcial como fallback.
     */
    private ChallengeSolution buildPartialSolution(List<Integer> orderIndices, StopWatch stopWatch) {
        Set<Integer> selectedOrders = new HashSet<>();
        Set<Integer> selectedAisles = new HashSet<>();
        int currentTotalUnits = 0;

        for (int orderIndex : orderIndices) {
            if (getRemainingTime(stopWatch) <= 0) break;
            Map<Integer, Integer> order = orders.get(orderIndex);
            int orderTotalUnits = order.values().stream().mapToInt(Integer::intValue).sum();
            if (currentTotalUnits + orderTotalUnits <= waveSizeUB) {
                Set<Integer> tempAisles = findMinimalAislesForOrder(order, selectedAisles);
                if (tempAisles != null) {
                    selectedOrders.add(orderIndex);
                    selectedAisles.addAll(tempAisles);
                    currentTotalUnits += orderTotalUnits;
                }
            }
        }
        return selectedOrders.isEmpty() ? null : new ChallengeSolution(selectedOrders, selectedAisles);
    }

    private List<Integer> orderByUnitsDescending() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) indices.add(i);
        indices.sort((a, b) -> Integer.compare(
            orders.get(b).values().stream().mapToInt(Integer::intValue).sum(),
            orders.get(a).values().stream().mapToInt(Integer::intValue).sum()
        ));
        return indices;
    }

    private List<Integer> orderByUnitsAscending() {
        List<Integer> indices = orderByUnitsDescending();
        Collections.reverse(indices);
        return indices;
    }

    private List<Integer> orderRandomly() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) indices.add(i);
        Collections.shuffle(indices, new Random());
        return indices;
    }

    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
            TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
            0
        );
    }

    protected boolean isSolutionFeasible(ChallengeSolution solution) {
        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();
        if (selectedOrders.isEmpty()) return false;

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) return false;

        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) return false;
        }
        return true;
    }

    protected double computeObjectiveFunction(ChallengeSolution solution) {
        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();
        if (selectedOrders.isEmpty() || visitedAisles.isEmpty()) return 0.0;

        int totalUnitsPicked = 0;
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream().mapToInt(Integer::intValue).sum();
        }
        return (double) totalUnitsPicked / visitedAisles.size();
    }
}