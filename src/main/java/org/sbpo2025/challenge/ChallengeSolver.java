package org.sbpo2025.challenge;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import java.util.*;
import java.util.concurrent.TimeUnit;
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

        // Process aisles
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

    private ILPSolution solveILP(InputData data, double lambda, long timeLimitMillis) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            System.err.println("Could not create SCIP solver.");
            return new ILPSolution(MPSolver.ResultStatus.ABNORMAL);
        }
        solver.setTimeLimit(timeLimitMillis);

        double infinity = MPSolver.infinity();

        MPVariable[] x = new MPVariable[data.numOrders];
        for (int o = 0; o < data.numOrders; ++o) {
            x[o] = solver.makeBoolVar("x_" + o);
        }

        MPVariable[] y = new MPVariable[data.numCorridors];
        for (int a = 0; a < data.numCorridors; ++a) {
            y[a] = solver.makeBoolVar("y_" + a);
        }

        MPObjective objective = solver.objective();
        for (int o = 0; o < data.numOrders; ++o) {
            objective.setCoefficient(x[o], data.totalItemsPerOrder.getOrDefault(o, 0));
        }
        for (int a = 0; a < data.numCorridors; ++a) {
            objective.setCoefficient(y[a], -lambda);
        }
        objective.setMaximization();

        MPConstraint totalItemsConstraint = solver.makeConstraint(data.lowerBoundItems, data.upperBoundItems, "TotalItems");
        for (int o = 0; o < data.numOrders; ++o) {
            totalItemsConstraint.setCoefficient(x[o], data.totalItemsPerOrder.getOrDefault(o, 0));
        }

        for (int i : data.allItems) {
            MPConstraint stockConstraint = solver.makeConstraint(-infinity, 0.0, "Stock_" + i);

            if (data.itemToOrders.containsKey(i)) {
                for (int o : data.itemToOrders.get(i)) {
                    int u_oi = data.orderItems.get(o).get(i);
                    stockConstraint.setCoefficient(x[o], u_oi);
                }
            }

            if (data.itemToCorridors.containsKey(i)) {
                for (int a : data.itemToCorridors.get(i)) {
                    if (data.corridorItems.containsKey(a) && data.corridorItems.get(a).containsKey(i)) {
                        int u_ai = data.corridorItems.get(a).get(i);
                        stockConstraint.setCoefficient(y[a], -u_ai);
                    }
                }
            }
        }

        System.out.println("  Solving ILP...");
        final MPSolver.ResultStatus resultStatus = solver.solve();
        System.out.println("  ILP Solve finished with status: " + resultStatus);

        if (resultStatus == MPSolver.ResultStatus.OPTIMAL || resultStatus == MPSolver.ResultStatus.FEASIBLE) {
            Set<Integer> selectedOrders = new HashSet<>();
            Set<Integer> selectedCorridors = new HashSet<>();
            int totalItems = 0;

            for (int o = 0; o < data.numOrders; ++o) {
                if (x[o].solutionValue() > 0.5) {
                    selectedOrders.add(o);
                    totalItems += data.totalItemsPerOrder.getOrDefault(o, 0);
                }
            }
            for (int a = 0; a < data.numCorridors; ++a) {
                if (y[a].solutionValue() > 0.5) {
                    selectedCorridors.add(a);
                }
            }

            return new ILPSolution(resultStatus, solver.objective().value(),
                                 selectedOrders, selectedCorridors,
                                 totalItems, selectedCorridors.size());
        } else {
            return new ILPSolution(resultStatus);
        }
    }
}