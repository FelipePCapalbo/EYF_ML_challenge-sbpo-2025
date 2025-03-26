package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // 10 minutos em milissegundos
    private static final int BASE_GROUP_SIZE = 10;
    private static final double TOLERANCE = 1e-2;

    protected int[][] ordersArray;
    protected int[][] aislesArray;
    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    protected Map<Integer, List<int[]>> itemToAisles;

    protected Order[] orderArray;
    protected Aisle[] aisleArray;
    protected Aisle[] aislesSorted;

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
        this.itemToAisles = new HashMap<>();

        // Pré-processamento com arrays
        ordersArray = new int[orders.size()][nItems];
        aislesArray = new int[aisles.size()][nItems];
        for (int o = 0; o < orders.size(); o++) {
            for (Map.Entry<Integer, Integer> entry : orders.get(o).entrySet()) {
                ordersArray[o][entry.getKey()] = entry.getValue();
            }
        }
        for (int a = 0; a < aisles.size(); a++) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                aislesArray[a][entry.getKey()] = entry.getValue();
            }
        }

        // Pré-processamento para heurística
        orderArray = new Order[orders.size()];
        aisleArray = new Aisle[aisles.size()];
        for (int o = 0; o < orders.size(); o++) {
            int size = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
            orderArray[o] = new Order(o, new HashMap<>(orders.get(o)), size);
        }
        for (int a = 0; a < aisles.size(); a++) {
            int size = aisles.get(a).values().stream().mapToInt(Integer::intValue).sum();
            aisleArray[a] = new Aisle(a, new HashMap<>(aisles.get(a)), size);
        }
        aislesSorted = Arrays.copyOf(aisleArray, aisleArray.length);
        Arrays.sort(aislesSorted, Comparator.comparingInt((Aisle a) -> a.size).reversed());
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        int nOrders = orders.size();
        int nAisles = aisles.size();

        System.out.println("--------------------------------------------------");
        System.out.printf("Iniciando resolução: %d pedidos, %d corredores%n", nOrders, nAisles);
        System.out.println("--------------------------------------------------");

        // Pré-processamento
        int[] totalUnits = new int[nOrders];
        for (int o = 0; o < nOrders; o++) totalUnits[o] = orderArray[o].size;

        for (int i = 0; i < nItems; i++) itemToAisles.put(i, new ArrayList<>());
        for (int a = 0; a < nAisles; a++) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(a).entrySet()) {
                itemToAisles.get(entry.getKey()).add(new int[]{a, entry.getValue()});
            }
        }

        // Solução inicial gulosa
        Cart initialCart = generateInitialSolution(totalUnits);
        ChallengeSolution initialSolution = new ChallengeSolution(initialCart.my_orders, initialCart.my_aisles);
        AtomicReference<ChallengeSolution> bestSolution = new AtomicReference<>(initialSolution);
        AtomicReference<Double> bestRatio = new AtomicReference<>(computeObjectiveFunction(initialSolution));

        if (isSolutionFeasible(initialSolution)) {
            System.out.printf("Solução inicial: Razão=%.2f | Pedidos=%d | Corredores=%d%n",
                    bestRatio.get(), initialSolution.orders().size(), initialSolution.aisles().size());
        } else {
            System.out.println("Solução inicial inviável! Retornando solução vazia.");
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }

        // Busca binária como refinamento
        double lower = bestRatio.get();
        double upper = greedyUpperBound();
        int iterations = binarySearchSolution(bestSolution, bestRatio, lower, upper, stopWatch);

        System.out.println("--------------------------------------------------");
        double totalTime = stopWatch.getTime(TimeUnit.SECONDS);
        System.out.printf("Concluído! Melhor razão=%.2f | Pedidos=%d | Corredores=%d | Tempo=%.2fs | Iterações=%d%n",
                bestRatio.get(), bestSolution.get().orders().size(), bestSolution.get().aisles().size(), totalTime, iterations);
        System.out.println("--------------------------------------------------");

        writeResults("binary", bestSolution.get(), stopWatch, 10, iterations);

        return bestSolution.get();
    }

    // Busca binária inspirada no código fornecido
    private int binarySearchSolution(AtomicReference<ChallengeSolution> bestSolution, AtomicReference<Double> bestRatio, double lower, double upper, StopWatch stopWatch) {
        int maxIterations = 10;
        int it = 0;

        while (it < maxIterations && upper - lower > TOLERANCE && getRemainingTime(stopWatch) > 0) {
            double k = (lower + upper) / 2;
            System.out.printf("Iteração %d | Intervalo: (%.2f, %.2f) | k=%.2f%n", it + 1, lower, upper, k);

            List<Integer> usedOrders = new ArrayList<>(bestSolution.get().orders());
            List<Integer> usedAisles = new ArrayList<>(bestSolution.get().aisles());
            double result = binaryMIP(k, usedOrders, usedAisles);

            if (result != -1) {
                ChallengeSolution newSolution = new ChallengeSolution(new HashSet<>(usedOrders), new HashSet<>(usedAisles));
                double newRatio = computeObjectiveFunction(newSolution);
                if (newRatio > bestRatio.get()) {
                    bestRatio.set(newRatio);
                    bestSolution.set(newSolution);
                    System.out.printf("  ** Melhor solução atualizada: Razão=%.2f **%n", newRatio);
                }
                lower = newRatio;
            } else {
                upper = k;
            }
            it++;
        }
        return it;
    }

    private double binaryMIP(double k, List<Integer> usedOrders, List<Integer> usedAisles) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.TimeLimit, 20.0);

            IloIntVar[] X = cplex.boolVarArray(ordersArray.length);
            IloIntVar[] Y = cplex.boolVarArray(aislesArray.length);

            // Restrições de tamanho
            IloLinearNumExpr waveSizeExpr = cplex.linearNumExpr();
            for (int o = 0; o < ordersArray.length; o++) {
                int size = Arrays.stream(ordersArray[o]).sum();
                waveSizeExpr.addTerm(size, X[o]);
            }
            cplex.addGe(waveSizeExpr, waveSizeLB);
            cplex.addLe(waveSizeExpr, waveSizeUB);

            // Restrições de disponibilidade
            for (int i = 0; i < nItems; i++) {
                IloLinearNumExpr demand = cplex.linearNumExpr();
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int o = 0; o < ordersArray.length; o++) {
                    demand.addTerm(ordersArray[o][i], X[o]);
                }
                for (int a = 0; a < aislesArray.length; a++) {
                    supply.addTerm(aislesArray[a][i], Y[a]);
                }
                cplex.addLe(demand, supply);
            }

            // Restrição de razão mínima
            IloLinearNumExpr exprX = cplex.linearNumExpr();
            IloLinearNumExpr exprKY = cplex.linearNumExpr();
            for (int o = 0; o < ordersArray.length; o++) {
                exprX.addTerm(Arrays.stream(ordersArray[o]).sum(), X[o]);
            }
            for (int a = 0; a < aislesArray.length; a++) {
                exprKY.addTerm(k, Y[a]);
            }
            cplex.addGe(exprX, exprKY);

            // Objetivo
            cplex.addMaximize(exprX);

            if (cplex.solve()) {
                usedOrders.clear();
                usedAisles.clear();
                for (int o = 0; o < ordersArray.length; o++) {
                    if (cplex.getValue(X[o]) > TOLERANCE) usedOrders.add(o);
                }
                for (int a = 0; a < aislesArray.length; a++) {
                    if (cplex.getValue(Y[a]) > TOLERANCE) usedAisles.add(a);
                }
                return cplex.getObjValue();
            }
            return -1;
        } catch (IloException e) {
            System.err.println("Erro no CPLEX: " + e.getMessage());
            return -1;
        }
    }

    // Upper bound guloso
    private double greedyUpperBound() {
        List<Double> sortedElementsPerAisle = Arrays.stream(aislesArray)
                .mapToDouble(row -> Arrays.stream(row).sum())
                .boxed()
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());

        double acum = 0;
        double upperBound = 0;
        for (int i = 0; i < sortedElementsPerAisle.size(); i++) {
            acum += sortedElementsPerAisle.get(i);
            upperBound = Math.max(upperBound, acum / (i + 1));
        }
        return upperBound;
    }

    // Logging
    private void writeResults(String strategy, ChallengeSolution solution, StopWatch stopWatch, int maxIterations, int iterations) {
        String filePath = "./results/results_" + strategy + "_" + maxIterations + ".csv";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, true))) {
            if (Files.size(Paths.get(filePath)) == 0) {
                writer.write("ordenes,pasillos,items,factibilidad,obj,tiempo,it\n");
            }
            writer.write(ordersArray.length + "," + aislesArray.length + "," + nItems + "," +
                    isSolutionFeasible(solution) + "," + computeObjectiveFunction(solution) + "," +
                    (MAX_RUNTIME / 1000 - getRemainingTime(stopWatch)) + "," + iterations + "\n");
        } catch (IOException e) {
            System.err.println("Erro ao escrever resultados: " + e.getMessage());
        }
    }

    // Classes internas
    protected static class Order {
        int id;
        Map<Integer, Integer> items;
        int size;

        Order(int id, Map<Integer, Integer> items, int size) {
            this.id = id;
            this.items = items;
            this.size = size;
        }
    }

    protected static class Aisle {
        int id;
        Map<Integer, Integer> items;
        int size;

        Aisle(int id, Map<Integer, Integer> items, int size) {
            this.id = id;
            this.items = items;
            this.size = size;
        }
    }

    protected class Cart {
        Set<Integer> my_orders;
        Set<Integer> my_aisles;
        Map<Integer, Integer> available;
        int cantItems;

        Cart() {
            my_orders = new HashSet<>();
            my_aisles = new HashSet<>();
            available = new HashMap<>();
            cantItems = 0;
        }

        void addAisle(Aisle a) {
            my_aisles.add(a.id);
            for (Map.Entry<Integer, Integer> entry : a.items.entrySet()) {
                available.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }

        void addOrder(Order o) {
            cantItems += o.size;
            my_orders.add(o.id);
        }

        boolean removeRequestIfPossible(Map<Integer, Integer> m) {
            for (Map.Entry<Integer, Integer> entry : m.entrySet()) {
                int elem = entry.getKey(), cant = entry.getValue();
                if (available.getOrDefault(elem, 0) < cant) return false;
            }
            for (Map.Entry<Integer, Integer> entry : m.entrySet()) {
                int elem = entry.getKey(), cant = entry.getValue();
                available.compute(elem, (k, v) -> v - cant);
            }
            return true;
        }

        void fill() {
            for (Order o : orderArray) {
                if (cantItems + o.size > waveSizeUB) continue;
                if (removeRequestIfPossible(o.items)) addOrder(o);
            }
        }

        void removeRedundantAisles() {
            for (Aisle p : aislesSorted) {
                if (my_aisles.contains(p.id) && removeRequestIfPossible(p.items)) {
                    my_aisles.remove(p.id);
                }
            }
        }
    }

    private Cart generateInitialSolution(int[] totalUnits) {
        Cart cart = new Cart();
        for (Aisle a : aislesSorted) { // Ordem decrescente
            cart.addAisle(a);
            cart.fill();
            cart.removeRedundantAisles();
            if (cart.cantItems >= waveSizeLB) break;
        }
        return cart;
    }

    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            System.out.println("nonFeasibleError: no orders or no aisles");
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        for (int order : selectedOrders) {
            for (int i = 0; i < nItems; i++) {
                totalUnitsPicked[i] += ordersArray[order][i];
            }
        }

        for (int aisle : visitedAisles) {
            for (int i = 0; i < nItems; i++) {
                totalUnitsAvailable[i] += aislesArray[aisle][i];
            }
        }

        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            System.out.println("nonFeasibleError: out of bounds solution");
            return false;
        }

        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                System.out.println("nonFeasibleError: element " + i + " was picked more times than available");
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
            totalUnitsPicked += Arrays.stream(ordersArray[order]).sum();
        }

        int numVisitedAisles = visitedAisles.size();
        return (double) totalUnitsPicked / numVisitedAisles;
    }
}