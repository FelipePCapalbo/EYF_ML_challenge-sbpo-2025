package org.sbpo2025.challenge;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

// Estrutura para armazenar os dados de entrada.
class InputData {
    int numOrders;
    int numItems;
    int numCorridors;
    Map<Integer, Map<Integer, Integer>> orderItems; // orderId -> {itemId -> quantity}
    Map<Integer, Map<Integer, Integer>> corridorItems; // corridorId -> {itemId -> quantity}
    int lowerBoundItems;
    int upperBoundItems;

    // --- Dados pré-computados para eficiência ---
    Set<Integer> allItems; // Conjunto de IDs de itens únicos
    Map<Integer, Integer> totalItemsPerOrder; // orderId -> quantidade total de itens
    Map<Integer, List<Integer>> itemToOrders; // itemId -> lista de orderIds que precisam do item
    Map<Integer, List<Integer>> itemToCorridors; // itemId -> lista de corridorIds que possuem o item

    public InputData(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles,
                     int nItems, int waveSizeLB, int waveSizeUB) {
        this.numOrders = orders.size();
        this.numItems = nItems;
        this.numCorridors = aisles.size();
        this.lowerBoundItems = waveSizeLB;
        this.upperBoundItems = waveSizeUB;

        // Inicializa os mapas
        this.orderItems = new HashMap<>();
        this.corridorItems = new HashMap<>();
        this.allItems = new HashSet<>();
        this.totalItemsPerOrder = new HashMap<>();
        this.itemToOrders = new HashMap<>();
        this.itemToCorridors = new HashMap<>();

        // Processa os pedidos
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

        // Processa os corredores (aisles)
        for (int i = 0; i < aisles.size(); i++) {
            Map<Integer, Integer> aisle = aisles.get(i);
            this.corridorItems.put(i, aisle);
            for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
                int itemId = entry.getKey();
                itemToCorridors.computeIfAbsent(itemId, k -> new ArrayList<>()).add(i);
            }
        }
    }
}

// Estrutura para armazenar o resultado de uma resolução do ILP
class ILPSolution {

    public enum SolverStatus { OPTIMAL, FEASIBLE, ABNORMAL }

    SolverStatus status;
    boolean isFeasible;
    double objectiveValue;
    Set<Integer> selectedOrders;
    Set<Integer> selectedCorridors;
    int totalItems;
    int numCorridors;

    public ILPSolution(SolverStatus status, double objectiveValue,
                       Set<Integer> selectedOrders, Set<Integer> selectedCorridors,
                       int totalItems, int numCorridors) {
        this.status = status;
        this.isFeasible = (status == SolverStatus.OPTIMAL || status == SolverStatus.FEASIBLE);
        this.objectiveValue = objectiveValue;
        this.selectedOrders = selectedOrders;
        this.selectedCorridors = selectedCorridors;
        this.totalItems = totalItems;
        this.numCorridors = numCorridors;
    }

    public ILPSolution(SolverStatus status) {
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

    public ChallengeSolution solve(StopWatch stopWatch) {
        long startTime = System.currentTimeMillis();
        long timeLimitMillis = TimeUnit.SECONDS.toMillis(TIME_LIMIT_SECONDS);

        ChallengeSolution bestOverallSolution = null;
        double bestOverallRatio = -1.0;
        double currentLambda = 0.0;

        System.out.println("Iniciando o algoritmo de Dinkelbach com CPLEX...");

        for (int k = 0; k < MAX_ITERATIONS; k++) {
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - startTime;
            long remainingTimeMillis = timeLimitMillis - elapsedTime;

            System.out.printf("Iteração %d, Lambda = %.5f, Tempo restante: %d ms%n",
                    k + 1, currentLambda, remainingTimeMillis);

            if (remainingTimeMillis <= 0) {
                System.out.println("Tempo limite alcançado. Encerrando o loop de Dinkelbach.");
                break;
            }

            ILPSolution ilpResult = solveILP(data, currentLambda, remainingTimeMillis);

            if (!ilpResult.isFeasible) {
                System.out.println("A resolução do ILP foi inviável ou falhou. Status: " + ilpResult.status);
                if (bestOverallSolution == null) {
                    System.err.println("Inviável na primeira iteração útil; o problema pode ser inviável.");
                } else {
                    System.out.println("Utilizando a melhor solução das iterações anteriores.");
                }
                break;
            }

            int currentTotalItems = ilpResult.totalItems;
            int currentNumCorridors = ilpResult.numCorridors;
            double Z = currentTotalItems - currentLambda * currentNumCorridors;

            System.out.printf("  Resultado ILP: Z=%.5f, Itens=%d, Corredores=%d%n",
                    Z, currentTotalItems, currentNumCorridors);

            if (currentNumCorridors > 0) {
                double currentRatio = (double) currentTotalItems / currentNumCorridors;
                if (currentRatio > bestOverallRatio) {
                    bestOverallRatio = currentRatio;
                    bestOverallSolution = new ChallengeSolution(
                            new HashSet<>(ilpResult.selectedOrders),
                            new HashSet<>(ilpResult.selectedCorridors)
                    );
                    System.out.printf("  *** Nova melhor razão encontrada: %.5f ***%n", bestOverallRatio);
                }
            } else if (currentTotalItems > 0) {
                System.err.println("Aviso: Solução encontrada com itens mas zero corredores.");
            }

            if (Math.abs(Z) < EPSILON) {
                System.out.println("Convergência alcançada (Z está próximo de 0).");
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
                System.err.println("Aviso: Não é possível atualizar lambda (divisão por zero).");
                break;
            }
        }

        if (bestOverallSolution == null) {
            System.err.println("Nenhuma solução viável encontrada no tempo ou iterações permitidas.");
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }

        System.out.printf("Fim do Dinkelbach. Melhor razão encontrada: %.5f%n", bestOverallRatio);
        return bestOverallSolution;
    }

    /**
     * Resolve uma iteração do ILP usando IBM ILOG CPLEX.
     */
    private ILPSolution solveILP(InputData data, double lambda, long timeLimitMillis) {
        try {
            IloCplex cplex = new IloCplex();

            // Configura o tempo limite (em segundos)
            cplex.setParam(IloCplex.Param.TimeLimit, timeLimitMillis / 1000.0);

            // Cria as variáveis booleanas para os pedidos (x) e para os corredores (y)
            IloNumVar[] x = new IloNumVar[data.numOrders];
            for (int o = 0; o < data.numOrders; o++) {
                x[o] = cplex.boolVar("x_" + o);
            }
            IloNumVar[] y = new IloNumVar[data.numCorridors];
            for (int a = 0; a < data.numCorridors; a++) {
                y[a] = cplex.boolVar("y_" + a);
            }

            // Restrição: lowerBoundItems <= Σ (totalItemsPerOrder[o] * x[o]) <= upperBoundItems.
            IloLinearNumExpr totalItemsExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.numOrders; o++) {
                int qty = data.totalItemsPerOrder.getOrDefault(o, 0);
                totalItemsExpr.addTerm(qty, x[o]);
            }
            cplex.addGe(totalItemsExpr, data.lowerBoundItems);
            cplex.addLe(totalItemsExpr, data.upperBoundItems);

            // Para cada item, impõe a restrição de estoque:
            // Σ (u_oi * x[o]) - Σ (u_ai * y[a]) ≤ 0.
            for (int item : data.allItems) {
                IloLinearNumExpr stockExpr = cplex.linearNumExpr();

                if (data.itemToOrders.containsKey(item)) {
                    for (int o : data.itemToOrders.get(item)) {
                        int u_oi = data.orderItems.get(o).get(item);
                        stockExpr.addTerm(u_oi, x[o]);
                    }
                }
                if (data.itemToCorridors.containsKey(item)) {
                    for (int a : data.itemToCorridors.get(item)) {
                        if (data.corridorItems.containsKey(a) && data.corridorItems.get(a).containsKey(item)) {
                            int u_ai = data.corridorItems.get(a).get(item);
                            stockExpr.addTerm(-u_ai, y[a]);
                        }
                    }
                }
                cplex.addLe(stockExpr, 0);
            }

            // Define a função objetivo:
            // Maximizar Σ (totalItemsPerOrder[o] * x[o]) - lambda * Σ y[a]
            IloLinearNumExpr objExpr = cplex.linearNumExpr();
            for (int o = 0; o < data.numOrders; o++) {
                int qty = data.totalItemsPerOrder.getOrDefault(o, 0);
                objExpr.addTerm(qty, x[o]);
            }
            for (int a = 0; a < data.numCorridors; a++) {
                objExpr.addTerm(-lambda, y[a]);
            }
            cplex.addMaximize(objExpr);

            // Resolve o modelo.
            boolean solved = cplex.solve();

            ILPSolution.SolverStatus status;
            if (solved && cplex.getStatus() == IloCplex.Status.Optimal) {
                status = ILPSolution.SolverStatus.OPTIMAL;
            } else if (solved && cplex.getStatus() == IloCplex.Status.Feasible) {
                status = ILPSolution.SolverStatus.FEASIBLE;
            } else {
                status = ILPSolution.SolverStatus.ABNORMAL;
            }

            if (solved && (cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible)) {
                Set<Integer> selectedOrders = new HashSet<>();
                Set<Integer> selectedCorridors = new HashSet<>();
                int totalItemsValue = 0;
                for (int o = 0; o < data.numOrders; o++) {
                    if (cplex.getValue(x[o]) > 0.5) {
                        selectedOrders.add(o);
                        totalItemsValue += data.totalItemsPerOrder.getOrDefault(o, 0);
                    }
                }
                for (int a = 0; a < data.numCorridors; a++) {
                    if (cplex.getValue(y[a]) > 0.5) {
                        selectedCorridors.add(a);
                    }
                }
                double computedObjective = totalItemsValue - lambda * selectedCorridors.size();
                cplex.end();
                return new ILPSolution(status, computedObjective, selectedOrders, selectedCorridors,
                        totalItemsValue, selectedCorridors.size());
            } else {
                cplex.end();
                return new ILPSolution(status);
            }
        } catch (IloException e) {
            e.printStackTrace();
            return new ILPSolution(ILPSolution.SolverStatus.ABNORMAL);
        }
    }
}
