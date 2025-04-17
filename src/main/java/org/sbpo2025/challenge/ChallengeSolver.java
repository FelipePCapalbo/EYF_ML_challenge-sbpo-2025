package org.sbpo2025.challenge;

<<<<<<< HEAD
import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.linearsolver.MPSolver;
=======
import ilog.concert.*;
import ilog.cplex.IloCplex;
>>>>>>> development
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
<<<<<<< HEAD
import java.util.stream.Collectors;

// Structure to hold parsed input data
class InputData {
    int numOrders;
    int numItems;
    int numCorridors;
    Map<Integer, Map<Integer, Integer>> orderItems; // orderId -> {itemId -> quantity}
    Map<Integer, Map<Integer, Integer>> corridorItems; // corridorId -> {itemId -> quantity}
    int lowerBoundItems;
    int upperBoundItems;

    // --- Precomputed data for efficiency ---
    Set<Integer> allItems; // Set of unique item IDs
    Map<Integer, Integer> totalItemsPerOrder; // orderId -> total quantity of items
    Map<Integer, List<Integer>> itemToOrders; // itemId -> list of orderIds needing it
    Map<Integer, List<Integer>> itemToCorridors; // itemId -> list of corridorIds having it
    Map<Integer, List<Integer>> itemToCorridorsWithQuantity; // itemId -> {corridorId -> quantity}

    public InputData(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles,
                     int nItems, int waveSizeLB, int waveSizeUB) {
        this.numOrders = orders.size();
        this.numItems = nItems;
        this.numCorridors = aisles.size();
        this.lowerBoundItems = waveSizeLB;
        this.upperBoundItems = waveSizeUB;

        // Initialize maps
        this.orderItems = new HashMap<>();
        this.corridorItems = new HashMap<>();
        this.allItems = new HashSet<>();
        this.totalItemsPerOrder = new HashMap<>();
        this.itemToOrders = new HashMap<>();
        this.itemToCorridors = new HashMap<>();
        this.itemToCorridorsWithQuantity = new HashMap<>();

        // Process orders
        for (int i = 0; i < orders.size(); i++) {
            Map<Integer, Integer> order = orders.get(i);
            this.orderItems.put(i, order);
            int totalItems = 0;
            for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                int itemId = entry.getKey();
                int quantity = entry.getValue();
                allItems.add(itemId);
                totalItems += quantity;
                itemToOrders.computeIfAbsent(itemId, k -> new ArrayList<>()).add(i);
            }
            totalItemsPerOrder.put(i, totalItems);
        }

        // Process aisles (corridors)
        for (int i = 0; i < aisles.size(); i++) {
            Map<Integer, Integer> aisle = aisles.get(i);
            this.corridorItems.put(i, aisle);
            for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
                int itemId = entry.getKey();
                int quantity = entry.getValue();
                itemToCorridors.computeIfAbsent(itemId, k -> new ArrayList<>()).add(i);
            }
        }
    }
}

// Structure to hold the result from one ILP solve
class ILPSolution {
    MPSolver.ResultStatus status;
    boolean isFeasible;
    double objectiveValue;
    Set<Integer> selectedOrders;
    Set<Integer> selectedCorridors;
    int totalItems;
    int numCorridors;

    public ILPSolution(MPSolver.ResultStatus status, double objectiveValue,
                       Set<Integer> selectedOrders, Set<Integer> selectedCorridors,
                       int totalItems, int numCorridors) {
        this.status = status;
        this.isFeasible = (status == MPSolver.ResultStatus.OPTIMAL ||
                           status == MPSolver.ResultStatus.FEASIBLE);
        this.objectiveValue = objectiveValue;
        this.selectedOrders = selectedOrders;
        this.selectedCorridors = selectedCorridors;
        this.totalItems = totalItems;
        this.numCorridors = numCorridors;
    }

    public ILPSolution(MPSolver.ResultStatus status) {
        this.status = status;
        this.isFeasible = false;
        this.objectiveValue = Double.NEGATIVE_INFINITY;
        this.selectedOrders = Collections.emptySet();
        this.selectedCorridors = Collections.emptySet();
        this.totalItems = 0;
        this.numCorridors = 0;
    }
}

public class ChallengeSolver {
    private static final int MAX_ITERATIONS = 100;
    private static final double EPSILON = 1e-6;
    private static final long TIME_LIMIT_SECONDS = 10 * 60 - 10;

    private final InputData data;

    public ChallengeSolver(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles,
                           int nItems, int waveSizeLB, int waveSizeUB) {
        this.data = new InputData(orders, aisles, nItems, waveSizeLB, waveSizeUB);
    }

    static {
        try {
            Loader.loadNativeLibraries();
        } catch (Exception e) {
            System.err.println("Failed to load OR-Tools native libraries: " + e);
        }
    }

    public ChallengeSolution solve(org.apache.commons.lang3.time.StopWatch stopWatch) {
        long startTime = System.currentTimeMillis();
        long timeLimitMillis = TimeUnit.SECONDS.toMillis(TIME_LIMIT_SECONDS);

        ChallengeSolution bestOverallSolution = null;
        double bestOverallRatio = -1.0;
        double currentLambda = 0.0;

        System.out.println("Starting Dinkelbach Algorithm...");

        for (int k = 0; k < MAX_ITERATIONS; k++) {
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - startTime;
            long remainingTimeMillis = timeLimitMillis - elapsedTime;

            System.out.printf("Iteration %d, Lambda = %.5f, Time Remaining: %d ms%n",
                    k + 1, currentLambda, remainingTimeMillis);

            if (remainingTimeMillis <= 0) {
                System.out.println("Time limit reached. Exiting Dinkelbach loop.");
                break;
            }

            ILPSolution ilpResult = solveILP(data, currentLambda, remainingTimeMillis);

            if (!ilpResult.isFeasible) {
                System.out.println("ILP solve was infeasible or failed. Status: " + ilpResult.status);
                if (bestOverallSolution == null) {
                    System.err.println("Infeasible on first useful iteration, problem might be infeasible.");
                } else {
                    System.out.println("Using best solution from previous iterations.");
                }
                break;
            }

            int currentTotalItems = ilpResult.totalItems;
            int currentNumCorridors = ilpResult.numCorridors;
            double Z = currentTotalItems - currentLambda * currentNumCorridors;

            System.out.printf("  ILP Result: Z=%.5f, Items=%d, Corridors=%d%n",
                    Z, currentTotalItems, currentNumCorridors);

            if (currentNumCorridors > 0) {
                double currentRatio = (double) currentTotalItems / currentNumCorridors;
                if (currentRatio > bestOverallRatio) {
                    bestOverallRatio = currentRatio;
                    bestOverallSolution = new ChallengeSolution(
                            new HashSet<>(ilpResult.selectedOrders),
                            new HashSet<>(ilpResult.selectedCorridors)
                    );
                    System.out.printf("  *** New best ratio found: %.5f ***%n", bestOverallRatio);
                }
            } else if (currentTotalItems > 0) {
                System.err.println("Warning: Found solution with items but zero corridors.");
            }

            if (Math.abs(Z) < EPSILON) {
                System.out.println("Convergence reached (Z is close to 0).");
                if (currentNumCorridors > 0) {
                    double finalRatio = (double) currentTotalItems / currentNumCorridors;
                    if (finalRatio > bestOverallRatio) {
                        bestOverallSolution = new ChallengeSolution(
                                new HashSet<>(ilpResult.selectedOrders),
                                new HashSet<>(ilpResult.selectedCorridors)
                        );
                        bestOverallRatio = finalRatio;
                    }
                }
                break;
            }

            if (currentNumCorridors > 0) {
                currentLambda = (double) currentTotalItems / currentNumCorridors;
            } else {
                System.err.println("Warning: Cannot update lambda, division by zero (0 corridors selected).");
                break;
            }
        }

        if (bestOverallSolution == null) {
            System.err.println("No feasible solution found within time limit or iterations.");
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }

        System.out.printf("Finished Dinkelbach. Best Ratio Found: %.5f%n", bestOverallRatio);
        return bestOverallSolution;
    }

    /**
     * Resolve uma iteração do ILP usando CP-SAT com paralelização.
     */
    private ILPSolution solveILP(InputData data, double lambda, long timeLimitMillis) {
        // Cria o modelo CP-SAT.
        CpModel model = new CpModel();

        // Cria as variáveis booleanas para os pedidos (x) e corredores (y).
        IntVar[] x = new IntVar[data.numOrders];
        for (int o = 0; o < data.numOrders; ++o) {
            x[o] = model.newBoolVar("x_" + o);
        }

        IntVar[] y = new IntVar[data.numCorridors];
        for (int a = 0; a < data.numCorridors; ++a) {
            y[a] = model.newBoolVar("y_" + a);
        }

        // Adiciona a restrição de total de itens:
        // lowerBoundItems <= Σ (totalItemsPerOrder[o] * x[o]) <= upperBoundItems.
        int[] ordersQuantities = new int[data.numOrders];
        for (int o = 0; o < data.numOrders; ++o) {
            ordersQuantities[o] = data.totalItemsPerOrder.getOrDefault(o, 0);
        }
        // Monta a expressão: sum_{o}(x[o] * ordersQuantities[o])
        // Utiliza explicitamente um array para a soma.
        LinearExpr totalItemsExpr = LinearExpr.constant(0);
        for (int o = 0; o < data.numOrders; o++) {
            totalItemsExpr = LinearExpr.sum(new LinearExpr[] { totalItemsExpr, LinearExpr.term(x[o], ordersQuantities[o]) });
        }
        model.addLinearConstraint(totalItemsExpr, data.lowerBoundItems, data.upperBoundItems);

        // Para cada item, impõe a restrição de estoque:
        // Σ (u_oi * x[o]) - Σ (u_ai * y[a]) ≤ 0.
        for (int item : data.allItems) {
            List<IntVar> vars = new ArrayList<>();
            List<Integer> coeffs = new ArrayList<>();

            if (data.itemToOrders.containsKey(item)) {
                for (int o : data.itemToOrders.get(item)) {
                    int u_oi = data.orderItems.get(o).get(item);
                    vars.add(x[o]);
                    coeffs.add(u_oi);
                }
            }

            if (data.itemToCorridors.containsKey(item)) {
                for (int a : data.itemToCorridors.get(item)) {
                    if (data.corridorItems.containsKey(a) && data.corridorItems.get(a).containsKey(item)) {
                        int u_ai = data.corridorItems.get(a).get(item);
                        vars.add(y[a]);
                        coeffs.add(-u_ai);
                    }
                }
            }

            int size = vars.size();
            if (size > 0) {
                IntVar[] varArray = vars.toArray(new IntVar[size]);
                int[] coeffsArray = new int[size];
                for (int i = 0; i < size; i++) {
                    coeffsArray[i] = coeffs.get(i);
                }
                // Monta a expressão: sum_{i}(varArray[i] * coeffsArray[i])
                LinearExpr stockExpr = LinearExpr.constant(0);
                for (int i = 0; i < size; i++) {
                    stockExpr = LinearExpr.sum(new LinearExpr[] { stockExpr, LinearExpr.term(varArray[i], coeffsArray[i]) });
                }
                model.addLinearConstraint(stockExpr, Integer.MIN_VALUE, 0);
            }
        }

        // Define a função objetivo:
        // Maximizar Σ (totalItemsPerOrder[o] * x[o]) - lambda * Σ y[a]
        // Como o CP-SAT exige coeficientes inteiros, usamos escalonamento.
        int scale = 1000000;
        int[] ordersCoeffs = new int[data.numOrders];
        for (int o = 0; o < data.numOrders; ++o) {
            ordersCoeffs[o] = data.totalItemsPerOrder.getOrDefault(o, 0) * scale;
        }
        int[] aislesCoeffs = new int[data.numCorridors];
        int lambdaScaled = (int) Math.round(lambda * scale);
        for (int a = 0; a < data.numCorridors; ++a) {
            aislesCoeffs[a] = -lambdaScaled;
        }
        // Monta as expressões parciais do objetivo.
        LinearExpr ordersExpr = LinearExpr.constant(0);
        for (int o = 0; o < data.numOrders; o++) {
            ordersExpr = LinearExpr.sum(new LinearExpr[] { ordersExpr, LinearExpr.term(x[o], ordersCoeffs[o]) });
        }
        LinearExpr aislesExpr = LinearExpr.constant(0);
        for (int a = 0; a < data.numCorridors; a++) {
            aislesExpr = LinearExpr.sum(new LinearExpr[] { aislesExpr, LinearExpr.term(y[a], aislesCoeffs[a]) });
        }
        LinearExpr objectiveExpr = LinearExpr.sum(new LinearExpr[] { ordersExpr, aislesExpr });
        model.maximize(objectiveExpr);

        // Cria o solver CP-SAT e configura o tempo limite e paralelização.
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(timeLimitMillis / 1000.0);
        int numThreads = Runtime.getRuntime().availableProcessors();
        solver.getParameters().setNumSearchWorkers(numThreads);

        // Resolve o modelo.
        CpSolverStatus status = solver.solve(model);

        // Mapeia o status do CP-SAT para MPSolver.ResultStatus para compatibilidade.
        MPSolver.ResultStatus resultStatus;
        if (status == CpSolverStatus.OPTIMAL) {
            resultStatus = MPSolver.ResultStatus.OPTIMAL;
        } else if (status == CpSolverStatus.FEASIBLE) {
            resultStatus = MPSolver.ResultStatus.FEASIBLE;
        } else {
            resultStatus = MPSolver.ResultStatus.ABNORMAL;
        }

        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            Set<Integer> selectedOrders = new HashSet<>();
            Set<Integer> selectedCorridors = new HashSet<>();
            int totalItems = 0;

            for (int o = 0; o < data.numOrders; o++) {
                if (solver.value(x[o]) == 1) {
                    selectedOrders.add(o);
                    totalItems += data.totalItemsPerOrder.getOrDefault(o, 0);
                }
            }
            for (int a = 0; a < data.numCorridors; a++) {
                if (solver.value(y[a]) == 1) {
                    selectedCorridors.add(a);
                }
            }
            // Calcula o valor do objetivo não escalonado para exibição: totalItems - lambda * (#corridors)
            double computedObjective = totalItems - lambda * selectedCorridors.size();
            return new ILPSolution(resultStatus, computedObjective,
                    selectedOrders, selectedCorridors, totalItems, selectedCorridors.size());
        } else {
            return new ILPSolution(resultStatus);
=======

public class ChallengeSolver {

    private static final int MAX_RESTARTS = 8;
    private static final long MAX_WALL_CLOCK_MS = 10 * 60 * 1000 - 5_000; // 10 min minus buffer
    private static final double EPS = 1e-6;

    private final ProblemData data;
    private StopWatch timer;

    public ChallengeSolver(List<Map<Integer, Integer>> orderList,
                           List<Map<Integer, Integer>> corridorList,
                           int itemTypes,
                           int minWaveItems,
                           int maxWaveItems) {
        this.data = new ProblemData(orderList, corridorList, itemTypes, minWaveItems, maxWaveItems);
    }

    public ChallengeSolution solve(StopWatch externalWatch) {
        this.timer = externalWatch;

        double bestRatio = -1;
        Set<Integer> bestOrders = null;
        Set<Integer> bestCorridors = null;

        Random random = new Random(2112);

        for (int restart = 0; restart < MAX_RESTARTS && remainingTimeMs() > 2_000; restart++) {

            if (data.corridorCount <= 20) {
                for (int corridorsTarget = 1;
                     corridorsTarget <= data.corridorCount && remainingTimeMs() > 2_000;
                     corridorsTarget++) {

                    IloResult res = solveFixedCorridorCount(corridorsTarget, remainingTimeMs());
                    if (res.feasible && res.ratio > bestRatio) {
                        bestRatio = res.ratio;
                        bestOrders = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                }
            } else {
                double lambda = random.nextDouble() * data.maxWaveItems;
                for (int iteration = 0; iteration < 50 && remainingTimeMs() > 2_000; iteration++) {
                    IloResult res = solveLambda(lambda, remainingTimeMs());
                    if (!res.feasible) break;

                    if (res.ratio > bestRatio) {
                        bestRatio = res.ratio;
                        bestOrders = res.chosenOrders;
                        bestCorridors = res.chosenCorridors;
                    }
                    if (res.corridorsUsed == 0) break;

                    double newLambda = (double) res.totalItems / res.corridorsUsed;
                    if (Math.abs(newLambda - lambda) < 1e-3) break;
                    lambda = newLambda;
                }
            }
        }

        if (bestOrders == null) {
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }
        return new ChallengeSolution(bestOrders, bestCorridors);
    }

    /* -----------------------  Private helpers ---------------------- */

    private IloResult solveFixedCorridorCount(int corridorsTarget, long timeMs) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);

            IloNumVar[] orderVar = new IloNumVar[data.orderCount];
            for (int o = 0; o < data.orderCount; o++) {
                orderVar[o] = cplex.boolVar("ord_" + o);
            }
            IloNumVar[] corridorVar = new IloNumVar[data.corridorCount];
            for (int a = 0; a < data.corridorCount; a++) {
                corridorVar[a] = cplex.boolVar("cor_" + a);
            }

            IloLinearNumExpr itemsExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) {
                itemsExpr.addTerm(data.itemsPerOrder[o], orderVar[o]);
            }
            cplex.addGe(itemsExpr, data.minWaveItems);
            cplex.addLe(itemsExpr, data.maxWaveItems);

            for (int item = 0; item < data.itemCount; item++) {
                if (data.ordersRequiringItem[item].isEmpty()) continue;
                IloLinearNumExpr required = cplex.linearNumExpr();
                for (int o : data.ordersRequiringItem[item]) {
                    required.addTerm(data.orderDemand.get(o).get(item), orderVar[o]);
                }
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int a : data.corridorsContainingItem[item]) {
                    supply.addTerm(data.corridorSupply.get(a).get(item), corridorVar[a]);
                }
                cplex.addLe(required, supply);
            }

            IloLinearNumExpr corridorExpr = cplex.linearNumExpr();
            for (IloNumVar v : corridorVar) corridorExpr.addTerm(1, v);
            cplex.addEq(corridorExpr, corridorsTarget);

            cplex.addMaximize(itemsExpr);

            if (!cplex.solve() ||
                    !(cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible)) {
                return IloResult.infeasible();
            }

            Set<Integer> ordersChosen = new HashSet<>();
            for (int o = 0; o < data.orderCount; o++) if (cplex.getValue(orderVar[o]) > .5) ordersChosen.add(o);
            Set<Integer> corridorsChosen = new HashSet<>();
            for (int a = 0; a < data.corridorCount; a++) if (cplex.getValue(corridorVar[a]) > .5) corridorsChosen.add(a);

            int itemsPicked = (int) Math.round(cplex.getValue(itemsExpr));
            double ratio = (double) itemsPicked / corridorsTarget;
            return new IloResult(true, ratio, itemsPicked, corridorsTarget, ordersChosen, corridorsChosen);

        } catch (IloException e) {
            return IloResult.infeasible();
        }
    }

    private IloResult solveLambda(double lambda, long timeMs) {
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.TimeLimit, timeMs / 1000.0);

            IloNumVar[] orderVar = new IloNumVar[data.orderCount];
            for (int o = 0; o < data.orderCount; o++) orderVar[o] = cplex.boolVar("ord_" + o);
            IloNumVar[] corridorVar = new IloNumVar[data.corridorCount];
            for (int a = 0; a < data.corridorCount; a++) corridorVar[a] = cplex.boolVar("cor_" + a);

            IloLinearNumExpr itemsExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) itemsExpr.addTerm(data.itemsPerOrder[o], orderVar[o]);
            cplex.addGe(itemsExpr, data.minWaveItems);
            cplex.addLe(itemsExpr, data.maxWaveItems);

            for (int item = 0; item < data.itemCount; item++) {
                if (data.ordersRequiringItem[item].isEmpty()) continue;
                IloLinearNumExpr required = cplex.linearNumExpr();
                for (int o : data.ordersRequiringItem[item]) {
                    required.addTerm(data.orderDemand.get(o).get(item), orderVar[o]);
                }
                IloLinearNumExpr supply = cplex.linearNumExpr();
                for (int a : data.corridorsContainingItem[item]) {
                    supply.addTerm(data.corridorSupply.get(a).get(item), corridorVar[a]);
                }
                cplex.addLe(required, supply);
            }

            IloLinearNumExpr obj = cplex.linearNumExpr();
            for (int o = 0; o < data.orderCount; o++) obj.addTerm(data.itemsPerOrder[o], orderVar[o]);
            for (int a = 0; a < data.corridorCount; a++) obj.addTerm(-lambda, corridorVar[a]);
            cplex.addMaximize(obj);

            if (!cplex.solve() ||
                    !(cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible)) {
                return IloResult.infeasible();
            }

            Set<Integer> ordersChosen = new HashSet<>();
            for (int o = 0; o < data.orderCount; o++) if (cplex.getValue(orderVar[o]) > .5) ordersChosen.add(o);
            Set<Integer> corridorsChosen = new HashSet<>();
            for (int a = 0; a < data.corridorCount; a++) if (cplex.getValue(corridorVar[a]) > .5) corridorsChosen.add(a);

            int itemsPicked = (int) Math.round(cplex.getValue(itemsExpr));
            int corridorsUsed = corridorsChosen.size();
            double ratio = corridorsUsed == 0 ? 0 : (double) itemsPicked / corridorsUsed;

            return new IloResult(true, ratio, itemsPicked, corridorsUsed, ordersChosen, corridorsChosen);

        } catch (IloException e) {
            return IloResult.infeasible();
        }
    }

    private long remainingTimeMs() {
        return Math.max(MAX_WALL_CLOCK_MS - timer.getTime(TimeUnit.MILLISECONDS), 0);
    }

    /* -------------------- inner helper classes -------------------- */

    private static class ProblemData {
        final int orderCount;
        final int itemCount;
        final int corridorCount;
        final int minWaveItems;
        final int maxWaveItems;

        final Map<Integer, Map<Integer, Integer>> orderDemand = new HashMap<>();
        final Map<Integer, Map<Integer, Integer>> corridorSupply = new HashMap<>();

        final int[] itemsPerOrder;
        final List<Integer>[] ordersRequiringItem;
        final List<Integer>[] corridorsContainingItem;

        @SuppressWarnings("unchecked")
        ProblemData(List<Map<Integer, Integer>> orders,
                    List<Map<Integer, Integer>> corridors,
                    int itemTypes,
                    int minWave,
                    int maxWave) {

            this.orderCount = orders.size();
            this.corridorCount = corridors.size();
            this.itemCount = itemTypes;
            this.minWaveItems = minWave;
            this.maxWaveItems = maxWave;

            this.itemsPerOrder = new int[orderCount];

            this.ordersRequiringItem = new List[itemCount];
            this.corridorsContainingItem = new List[itemCount];
            for (int i = 0; i < itemCount; i++) {
                ordersRequiringItem[i] = new ArrayList<>();
                corridorsContainingItem[i] = new ArrayList<>();
            }

            for (int o = 0; o < orderCount; o++) {
                Map<Integer, Integer> map = orders.get(o);
                orderDemand.put(o, map);
                int total = 0;
                for (Map.Entry<Integer, Integer> e : map.entrySet()) {
                    int item = e.getKey();
                    int q = e.getValue();
                    total += q;
                    ordersRequiringItem[item].add(o);
                }
                itemsPerOrder[o] = total;
            }

            for (int a = 0; a < corridorCount; a++) {
                Map<Integer, Integer> map = corridors.get(a);
                corridorSupply.put(a, map);
                for (int item : map.keySet()) {
                    corridorsContainingItem[item].add(a);
                }
            }
        }
    }

    private static class IloResult {
        final boolean feasible;
        final double ratio;
        final int totalItems;
        final int corridorsUsed;
        final Set<Integer> chosenOrders;
        final Set<Integer> chosenCorridors;

        private IloResult(boolean feasible,
                          double ratio,
                          int totalItems,
                          int corridorsUsed,
                          Set<Integer> chosenOrders,
                          Set<Integer> chosenCorridors) {
            this.feasible = feasible;
            this.ratio = ratio;
            this.totalItems = totalItems;
            this.corridorsUsed = corridorsUsed;
            this.chosenOrders = chosenOrders;
            this.chosenCorridors = chosenCorridors;
        }

        static IloResult infeasible() {
            return new IloResult(false, -1, 0, 0,
                    Collections.emptySet(), Collections.emptySet());
>>>>>>> development
        }
    }
}
