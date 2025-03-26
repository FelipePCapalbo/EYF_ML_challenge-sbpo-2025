package org.sbpo2025.challenge;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes
    private final double EPSILON = 0.001;
    private final double TOLERANCE = Math.exp(-6);
    private final int WINDOW_SIZE = 5; // Number of orders to fix per iteration

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;
    
    private ChallengeSolution bestSolution = null;
    private double bestObjective = Double.NEGATIVE_INFINITY;

    private static class Logger {
        static void info(String message) {
            System.out.printf("[INFO] %s | Time: %.2fs%n", message, 
                (double) System.currentTimeMillis() / 1000);
        }
        
        static void debug(String message) {
            System.out.printf("[DEBUG] %s | Time: %.2fs%n", message, 
                (double) System.currentTimeMillis() / 1000);
        }
    }

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, 
            int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        try {
            Logger.info("Starting Relax-and-Fix solver");
            Logger.info(String.format("Problem size - Orders: %d, Aisles: %d, Items: %d", 
                orders.size(), aisles.size(), nItems));

            IloCplex cplex = new IloCplex();
            cplex.setParam(IloCplex.Param.Threads, Runtime.getRuntime().availableProcessors());
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, TOLERANCE);
            cplex.setOut(null);

            IloIntVar[] w = new IloIntVar[orders.size()];
            IloIntVar[] a = new IloIntVar[aisles.size()];
            for (int i = 0; i < orders.size(); i++) {
                w[i] = cplex.boolVar("W_" + i);
            }
            for (int i = 0; i < aisles.size(); i++) {
                a[i] = cplex.boolVar("A_" + i);
            }

            addBaseConstraints(cplex, w, a);

            IloLinearNumExpr itemsPicked = cplex.linearNumExpr();
            for (int o = 0; o < orders.size(); o++) {
                int totalUnits = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
                itemsPicked.addTerm(totalUnits, w[o]);
            }

            int nWindows = (int) Math.ceil((double) orders.size() / WINDOW_SIZE);
            boolean[] fixedW = new boolean[orders.size()];
            List<IloConstraint> fixedConstraints = new ArrayList<>();

            for (int window = 0; window < nWindows && getRemainingTime(stopWatch) > 0; window++) {
                long remainingTimeSec = getRemainingTime(stopWatch);
                Logger.info(String.format("Processing window %d/%d | Remaining time: %ds", 
                    window + 1, nWindows, remainingTimeSec));

                int start = window * WINDOW_SIZE;
                int end = Math.min(start + WINDOW_SIZE, orders.size());

                for (int i = 0; i < orders.size(); i++) {
                    if (!fixedW[i]) {
                        w[i].setLB(0.0);
                        w[i].setUB(1.0);
                    }
                }

                cplex.clearModel();
                addBaseConstraints(cplex, w, a);
                for (IloConstraint constraint : fixedConstraints) {
                    cplex.add(constraint);
                }
                cplex.addMaximize(itemsPicked);

                double timeLimit = Math.max(remainingTimeSec / 1000.0, 1.0);
                cplex.setParam(IloCplex.Param.TimeLimit, timeLimit);

                if (cplex.solve()) {
                    List<Boolean> wSol = Arrays.stream(w)
                            .map(var -> {
                                try {
                                    return cplex.getValue(var) >= 0.5;
                                } catch (IloException e) {
                                    return false;
                                }
                            })
                            .collect(Collectors.toList());
                    List<Boolean> aSol = Arrays.stream(a)
                            .map(var -> {
                                try {
                                    return cplex.getValue(var) >= 0.5;
                                } catch (IloException e) {
                                    return false;
                                }
                            })
                            .collect(Collectors.toList());

                    Set<Integer> selectedOrders = IntStream.range(0, wSol.size())
                            .filter(wSol::get)
                            .boxed()
                            .collect(Collectors.toSet());
                    Set<Integer> selectedAisles = IntStream.range(0, aSol.size())
                            .filter(aSol::get)
                            .boxed()
                            .collect(Collectors.toSet());

                    ChallengeSolution currentSolution = new ChallengeSolution(selectedOrders, selectedAisles);
                    double currentObjective = optimizeAisles(cplex, w, a, selectedOrders, 
                        computeObjectiveFunction(currentSolution) * selectedAisles.size());

                    if (currentObjective > this.bestObjective && isSolutionFeasible(currentSolution)) {
                        this.bestObjective = currentObjective;
                        this.bestSolution = currentSolution;
                        Logger.info(String.format("New best solution found | Objective: %.2f | Orders: %d | Aisles: %d", 
                            this.bestObjective, selectedOrders.size(), selectedAisles.size()));
                    }

                    // Fix only if solution improves and remains feasible
                    if (isSolutionFeasible(currentSolution)) {
                        for (int i = start; i < end; i++) {
                            if (!fixedW[i]) {
                                fixedW[i] = true;
                                double value = cplex.getValue(w[i]);
                                fixedConstraints.add(cplex.addEq(w[i], value >= 0.5 ? 1 : 0));
                            }
                        }
                    }
                } else {
                    Logger.debug(String.format("No solution found for window %d", window + 1));
                }
            }

            cplex.end();
            if (this.bestSolution == null) {
                Logger.info("No feasible solution found with CPLEX, trying initial solution");
                this.bestSolution = getInitialFeasibleSolution();
            }
            
            Logger.info(String.format("Solver finished | Final objective: %.2f | Total time: %.2fs", 
                computeObjectiveFunction(this.bestSolution), stopWatch.getTime(TimeUnit.MILLISECONDS) / 1000.0));
            return this.bestSolution;

        } catch (IloException e) {
            Logger.info("Exception occurred: " + e.getMessage());
            if (e.getMessage().contains("1016")) {
                Logger.info("CPLEX size limit exceeded, falling back to greedy heuristic");
                this.bestSolution = getGreedySolution();
            }
            return this.bestSolution != null ? this.bestSolution : getInitialFeasibleSolution();
        }
    }

    private void addBaseConstraints(IloCplex cplex, IloIntVar[] w, IloIntVar[] a) throws IloException {
        IloLinearNumExpr waveSize = cplex.linearNumExpr();
        for (int o = 0; o < orders.size(); o++) {
            int totalUnits = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
            waveSize.addTerm(totalUnits, w[o]);
        }
        cplex.addGe(waveSize, waveSizeLB);
        cplex.addLe(waveSize, waveSizeUB);

        for (int i = 0; i < nItems; i++) {
            IloLinearNumExpr demand = cplex.linearNumExpr();
            IloLinearNumExpr supply = cplex.linearNumExpr();
            
            for (int o = 0; o < orders.size(); o++) {
                int qty = orders.get(o).getOrDefault(i, 0);
                demand.addTerm(qty, w[o]);
            }
            for (int j = 0; j < aisles.size(); j++) {
                int qty = aisles.get(j).getOrDefault(i, 0);
                supply.addTerm(qty, a[j]);
            }
            cplex.addLe(demand, supply);
        }
    }

    private double optimizeAisles(IloCplex cplex, IloIntVar[] w, IloIntVar[] a, 
                                Set<Integer> fixedOrders, double items) throws IloException {
        cplex.clearModel();
        addBaseConstraints(cplex, w, a);

        for (int i = 0; i < w.length; i++) {
            cplex.addEq(w[i], fixedOrders.contains(i) ? 1 : 0);
        }

        IloLinearNumExpr numAisles = cplex.linearNumExpr();
        for (IloIntVar aisle : a) {
            numAisles.addTerm(1, aisle);
        }
        cplex.addMinimize(numAisles);

        if (cplex.solve()) {
            Set<Integer> selectedAisles = IntStream.range(0, a.length)
                    .filter(i -> {
                        try {
                            return cplex.getValue(a[i]) >= 0.5;
                        } catch (IloException e) {
                            return false;
                        }
                    })
                    .boxed()
                    .collect(Collectors.toSet());
            return items / selectedAisles.size();
        }
        return Double.NEGATIVE_INFINITY;
    }

    private ChallengeSolution getInitialFeasibleSolution() {
        Set<Integer> ordersSet = new HashSet<>();
        Set<Integer> aislesSet = new HashSet<>();
        int totalUnits = 0;
        int[] itemDemand = new int[nItems];
        int[] itemSupply = new int[nItems];

        // Greedily select orders
        List<Integer> orderIndices = IntStream.range(0, orders.size())
                .boxed()
                .sorted((i1, i2) -> Integer.compare(
                    orders.get(i2).values().stream().mapToInt(Integer::intValue).sum(),
                    orders.get(i1).values().stream().mapToInt(Integer::intValue).sum()))
                .collect(Collectors.toList());

        for (int o : orderIndices) {
            int units = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
            if (totalUnits + units <= waveSizeUB) {
                ordersSet.add(o);
                totalUnits += units;
                orders.get(o).forEach((item, qty) -> itemDemand[item] += qty);
            }
            if (totalUnits >= waveSizeLB) break;
        }

        // Select minimal aisles to cover demand
        for (int a = 0; a < aisles.size(); a++) {
            boolean needed = false;
            for (int i = 0; i < nItems; i++) {
                if (itemDemand[i] > itemSupply[i] && aisles.get(a).containsKey(i)) {
                    needed = true;
                    break;
                }
            }
            if (needed) {
                aislesSet.add(a);
                aisles.get(a).forEach((item, qty) -> itemSupply[item] += qty);
            }
        }

        ChallengeSolution solution = new ChallengeSolution(ordersSet, aislesSet);
        if (isSolutionFeasible(solution)) {
            Logger.debug("Initial feasible solution generated");
            return solution;
        }
        Logger.debug("No feasible initial solution possible");
        return new ChallengeSolution(Set.of(), Set.of());
    }

    private ChallengeSolution getGreedySolution() {
        Set<Integer> ordersSet = new HashSet<>();
        Set<Integer> aislesSet = new HashSet<>();
        int totalUnits = 0;
        int[] itemDemand = new int[nItems];
        int[] itemSupply = new int[nItems];

        List<Integer> orderIndices = IntStream.range(0, orders.size())
                .boxed()
                .sorted((i1, i2) -> Integer.compare(
                    orders.get(i2).values().stream().mapToInt(Integer::intValue).sum(),
                    orders.get(i1).values().stream().mapToInt(Integer::intValue).sum()))
                .collect(Collectors.toList());

        for (int o : orderIndices) {
            int units = orders.get(o).values().stream().mapToInt(Integer::intValue).sum();
            if (totalUnits + units <= waveSizeUB) {
                ordersSet.add(o);
                totalUnits += units;
                orders.get(o).forEach((item, qty) -> itemDemand[item] += qty);
            }
            if (totalUnits >= waveSizeLB) break;
        }

        List<Integer> aisleIndices = new ArrayList<>();
        for (int a = 0; a < aisles.size(); a++) {
            int score = aisles.get(a).entrySet().stream()
                    .mapToInt(e -> Math.min(e.getValue(), Math.max(0, itemDemand[e.getKey()] - itemSupply[e.getKey()])))
                    .sum();
            aisleIndices.add(a);
        }
        aisleIndices.sort((a1, a2) -> Integer.compare(
            aisles.get(a2).entrySet().stream().mapToInt(e -> Math.min(e.getValue(), Math.max(0, itemDemand[e.getKey()] - itemSupply[e.getKey()]))).sum(),
            aisles.get(a1).entrySet().stream().mapToInt(e -> Math.min(e.getValue(), Math.max(0, itemDemand[e.getKey()] - itemSupply[e.getKey()]))).sum()));

        for (int a : aisleIndices) {
            aislesSet.add(a);
            aisles.get(a).forEach((item, qty) -> itemSupply[item] += qty);
            boolean feasible = true;
            for (int i = 0; i < nItems; i++) {
                if (itemDemand[i] > itemSupply[i]) {
                    feasible = false;
                    break;
                }
            }
            if (feasible) break;
        }

        ChallengeSolution solution = new ChallengeSolution(ordersSet, aislesSet);
        if (isSolutionFeasible(solution)) {
            this.bestObjective = computeObjectiveFunction(solution);
            return solution;
        }
        return getInitialFeasibleSolution();
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
            return false;
        }

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

        return (double) totalUnitsPicked / visitedAisles.size();
    }
}