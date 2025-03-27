package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 540000; // milliseconds; 5 s
    private final int NUM_THREADS = Runtime.getRuntime().availableProcessors();
    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;

    public ChallengeSolver(
            List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    public static class Individual {
        protected ArrayList<Boolean> genome;
        protected double fitness;

        public Individual(ArrayList<Boolean> genome, double fitness) {
            this.genome = genome;
            this.fitness = fitness;
        }

    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        // Create a ForkJoinPool to manage all parallel operations
        ForkJoinPool customThreadPool = new ForkJoinPool(NUM_THREADS);

        try {
            final int populationSize = 100;
            final int maxIterations = Integer.MAX_VALUE;

            // Run the genetic algorithm in the custom thread pool
            Individual GaIndividual = customThreadPool.submit(() ->
                    geneticAlgorithmWithImprovedSelection(populationSize, maxIterations,stopWatch)
            ).get(); // This blocks until the result is available

            // Apply post-processing steps sequentially
            GaIndividual = removeUnusedAisles(GaIndividual);
            GaIndividual = addOrdersWithoutNewAisles(GaIndividual);

            if(GaIndividual != null) {
                System.out.println(" Score GA:" + GaIndividual.fitness);
            }

            // Force all parallel streams to complete by shutting down the pool
            customThreadPool.shutdown();
            try {
                // Wait for all tasks to complete with a reasonable timeout
                if (!customThreadPool.awaitTermination(3, TimeUnit.SECONDS)) {
                    customThreadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                customThreadPool.shutdownNow();
            }

            // Now that all threads are complete, return the solution
            return decodeIndividual(GaIndividual);
        }
        catch (Exception e) {
            System.err.println("Error in GA: " + e.getMessage());
            customThreadPool.shutdownNow();
            return new ChallengeSolution(new HashSet<>(), new HashSet<>());
        }
    }

    public ArrayList<Individual> generateInitialPopulation(int size) {
        ArrayList<Individual> population = new ArrayList<>();
        Random rand = new Random();

        List<Integer> orderIndexes = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            orderIndexes.add(i);
        }


        for (int i = 0; i < size; i++) {
            ArrayList<Boolean> genome = new ArrayList<>(Collections.nCopies(orders.size() + aisles.size(), false));
            int pickedUnits = 0;
            List<Integer> localOrderIndexes = new ArrayList<>(orderIndexes);
            List<Integer> localAisleIndexes = new ArrayList<>();
            List<Map<Integer, Integer>> unitsTakenFromAisles = new ArrayList<>(Collections.nCopies(aisles.size(), new HashMap<>()));


            while (!localOrderIndexes.isEmpty()) {
                int orderIndex = rand.nextInt(localOrderIndexes.size());
                int pickedOrder = localOrderIndexes.get(orderIndex);
                int orderUnitsSum = orders.get(pickedOrder).values().stream().mapToInt(Integer::intValue).sum();

                localOrderIndexes.remove(orderIndex);

                if (pickedUnits + orderUnitsSum > waveSizeUB) continue;

                int satisfiedItems = 0;
                int totalItems = orders.get(pickedOrder).size();

                for (Map.Entry<Integer, Integer> entry : orders.get(pickedOrder).entrySet()) {
                    int item = entry.getKey();
                    int quantity = entry.getValue();

                    for (int aisleIndex = 0; aisleIndex < this.aisles.size(); aisleIndex++) {
                        Map<Integer, Integer> aisle = this.aisles.get(aisleIndex);
                        if (aisle.containsKey(item)) {
                            int amountTakenFromItemInAisle = unitsTakenFromAisles.get(aisleIndex).getOrDefault(item, 0);
                            int amountAvailableInAisle = aisle.get(item);
                            if (amountTakenFromItemInAisle == amountAvailableInAisle) continue;
                            if (!localAisleIndexes.contains(aisleIndex)) localAisleIndexes.add(aisleIndex);
                            int newItemInAisleAmount = Math.min(amountAvailableInAisle, amountTakenFromItemInAisle + quantity);
                            unitsTakenFromAisles.get(aisleIndex).put(item, newItemInAisleAmount);
                            genome.set(orders.size() + aisleIndex, true);
                            if (amountTakenFromItemInAisle + quantity <= amountAvailableInAisle) {
                                satisfiedItems++;
                                break;
                            }
                            quantity -= amountAvailableInAisle - amountTakenFromItemInAisle;
                        }
                    }
                }
                if (satisfiedItems == totalItems) {
                    pickedUnits += orderUnitsSum;
                    genome.set(pickedOrder, true);
                }
            }
            population.add(new Individual(genome, -1));
        }
        return population;
    }

    public ChallengeSolution decodeIndividual(Individual individual) {
        Set<Integer> selectedOrders = new HashSet<>();
        Set<Integer> selectedAisles = new HashSet<>();

        for (int i = 0; i < orders.size(); i++) {
            if (individual.genome.get(i)) {
                selectedOrders.add(i);
            }
        }

        for (int i = 0; i < aisles.size(); i++) {
            if (individual.genome.get(orders.size() + i)) {
                selectedAisles.add(i);
            }
        }

        return new ChallengeSolution(selectedOrders, selectedAisles);
    }


    public Individual crossover(Individual parent1, Individual parent2) {
        Random rand = new Random();
        ArrayList<Boolean> childGenome = new ArrayList<>(Collections.nCopies(orders.size() + aisles.size(), false));
        Set<Integer> selectedOrders = new HashSet<>();
        Set<Integer> selectedAisles = new HashSet<>();
        int totalUnits = 0;

        // Pre-compute order units with parallel streams
        Map<Integer, Integer> orderUnitsMap = new ConcurrentHashMap<>();
        IntStream.range(0, orders.size())
                .parallel()
                .forEach(i -> {
                    int units = orders.get(i).values().stream()
                            .mapToInt(Integer::intValue)
                            .sum();
                    orderUnitsMap.put(i, units);
                });

        // Sequential selection from parents
        for (int i = 0; i < orders.size(); i++) {
            if ((parent1.genome.get(i) || parent2.genome.get(i)) && rand.nextBoolean()) {
                int orderUnits = orderUnitsMap.get(i);
                if (totalUnits + orderUnits <= waveSizeUB) {
                    selectedOrders.add(i);
                    totalUnits += orderUnits;
                }
            }
        }

        // Sequential addition of more orders if needed
        List<Integer> remainingOrders = IntStream.range(0, orders.size())
                .filter(i -> !selectedOrders.contains(i))
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(remainingOrders);

        for (int i : remainingOrders) {
            int orderUnits = orderUnitsMap.get(i);
            if (totalUnits + orderUnits <= waveSizeUB) {
                selectedOrders.add(i);
                totalUnits += orderUnits;
                if (totalUnits >= waveSizeLB) break;
            }
        }

        // Set orders in genome
        selectedOrders.forEach(orderIndex -> childGenome.set(orderIndex, true));

        // Create item to aisles mapping - this is the most expensive part, now parallelized
        Map<Integer, Set<Integer>> itemToAislesMap = new ConcurrentHashMap<>();

        // Parallel computation of item-to-aisles mapping
        IntStream.range(0, aisles.size())
                .parallel()
                .forEach(aisleIndex -> {
                    Map<Integer, Integer> aisle = aisles.get(aisleIndex);
                    aisle.keySet().forEach(item ->
                            itemToAislesMap.computeIfAbsent(item, k -> ConcurrentHashMap.newKeySet())
                                    .add(aisleIndex)
                    );
                });

        // Find required aisles for selected orders
        selectedOrders.forEach(orderIndex ->
                orders.get(orderIndex).keySet().forEach(item -> {
                    Set<Integer> aislesForItem = itemToAislesMap.get(item);
                    if (aislesForItem != null) {
                        selectedAisles.addAll(aislesForItem);
                    }
                })
        );

        // Set aisles in genome
        selectedAisles.forEach(aisleIndex ->
                childGenome.set(orders.size() + aisleIndex, true));

        ChallengeSolution atual = new ChallengeSolution(selectedOrders, selectedAisles);
        Individual child = new Individual(childGenome, computeObjectiveFunction(atual));
        improvedMutate(child);
        return child;
    }


    /**
     * Improved mutation strategy to avoid local maxima by removing a percentage of orders.
     * @param individual Individual to mutate
     * @param percentToRemove Percentage of orders to remove (0.0 to 1.0)
     * @return Mutated individual
     */
    public Individual mutateRemovePercentageOfOrders(Individual individual, double percentToRemove) {
        if (new Random().nextDouble() > 0.3) return individual; // Apply mutation with 30% probability

        ChallengeSolution solution = decodeIndividual(individual);
        Set<Integer> selectedOrders = new HashSet<>(solution.orders());

        if (selectedOrders.isEmpty()) return individual;

        // Determine number of orders to remove
        int ordersToRemove = Math.max(1, (int)(selectedOrders.size() * percentToRemove));

        // Select orders to remove randomly
        List<Integer> ordersList = new ArrayList<>(selectedOrders);
        Collections.shuffle(ordersList);
        Set<Integer> ordersToKeep = new HashSet<>(ordersList.subList(ordersToRemove, ordersList.size()));

        // Create a new solution with only the orders to keep
        Set<Integer> newAisles = findMinimumRequiredAisles(ordersToKeep);

        // Update individual's genome
        updateGenome(individual, ordersToKeep, newAisles);

        // Try to add more orders without adding new aisles
        return addOrdersWithoutNewAisles(individual);
    }

    /**
     * Find the minimum set of aisles required to fulfill the given orders.
     * Uses parallelism for performance with large datasets.
     */
    private Set<Integer> findMinimumRequiredAisles(Set<Integer> orderSet) {
        // First identify all items required by the orders
        Map<Integer, Integer> requiredItems = new ConcurrentHashMap<>();

        orderSet.parallelStream().forEach(orderIndex -> {
            Map<Integer, Integer> order = orders.get(orderIndex);
            synchronized(requiredItems) {
                order.forEach((item, quantity) ->
                        requiredItems.put(item, requiredItems.getOrDefault(item, 0) + quantity));
            }
        });

        // Create an item-to-aisles mapping
        Map<Integer, List<AisleQuantity>> itemToAislesMap = createItemToAislesMap();

        // Find the minimum set of aisles to satisfy all required items
        return findMinimumAisleSet(requiredItems, itemToAislesMap);
    }

    /**
     * Helper class to track available quantities in aisles
     */
    private static class AisleQuantity {
        final int aisleIndex;
        final int quantity;

        AisleQuantity(int aisleIndex, int quantity) {
            this.aisleIndex = aisleIndex;
            this.quantity = quantity;
        }
    }

    /**
     * Creates a mapping from items to the aisles that contain them and their quantities.
     * This is computed in parallel for better performance.
     */
    private Map<Integer, List<AisleQuantity>> createItemToAislesMap() {
        Map<Integer, List<AisleQuantity>> itemToAislesMap = new ConcurrentHashMap<>();

        IntStream.range(0, aisles.size()).parallel().forEach(aisleIndex -> {
            Map<Integer, Integer> aisle = aisles.get(aisleIndex);
            aisle.forEach((item, quantity) -> {
                synchronized(itemToAislesMap) {
                    itemToAislesMap.computeIfAbsent(item, k -> new ArrayList<>())
                            .add(new AisleQuantity(aisleIndex, quantity));
                }
            });
        });

        return itemToAislesMap;
    }

    /**
     * Finds the minimum set of aisles needed to fulfill all required items.
     * Uses a greedy approach that prioritizes aisles with the most needed items.
     */
    private Set<Integer> findMinimumAisleSet(Map<Integer, Integer> requiredItems,
                                             Map<Integer, List<AisleQuantity>> itemToAislesMap) {
        Set<Integer> selectedAisles = new HashSet<>();
        Map<Integer, Integer> remainingNeeded = new HashMap<>(requiredItems);

        // Continue until all items are fulfilled
        while (!remainingNeeded.isEmpty()) {
            // Compute value for each aisle (how many needed items it can provide)
            Map<Integer, Double> aisleValues = new HashMap<>();

            for (Map.Entry<Integer, Integer> entry : remainingNeeded.entrySet()) {
                int item = entry.getKey();
                int neededQuantity = entry.getValue();

                List<AisleQuantity> aislesWithItem = itemToAislesMap.getOrDefault(item, Collections.emptyList());
                for (AisleQuantity aq : aislesWithItem) {
                    if (!selectedAisles.contains(aq.aisleIndex)) {
                        double value = Math.min(aq.quantity, neededQuantity);
                        aisleValues.put(aq.aisleIndex,
                                aisleValues.getOrDefault(aq.aisleIndex, 0.0) + value);
                    }
                }
            }

            if (aisleValues.isEmpty()) break; // No more aisles can help

            // Select the aisle that provides the most needed items
            int bestAisle = aisleValues.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(-1);

            if (bestAisle == -1) break;

            selectedAisles.add(bestAisle);

            // Update remaining needs
            Map<Integer, Integer> aisle = aisles.get(bestAisle);
            for (Map.Entry<Integer, Integer> entry : aisle.entrySet()) {
                int item = entry.getKey();
                int quantity = entry.getValue();

                if (remainingNeeded.containsKey(item)) {
                    int newNeeded = remainingNeeded.get(item) - quantity;
                    if (newNeeded <= 0) {
                        remainingNeeded.remove(item);
                    } else {
                        remainingNeeded.put(item, newNeeded);
                    }
                }
            }
        }

        return selectedAisles;
    }

    /**
     * Update the genome of an individual based on sets of orders and aisles.
     */
    private void updateGenome(Individual individual, Set<Integer> orders, Set<Integer> aisles) {
        // Reset genome
        individual.genome.replaceAll(ignored -> false);

        // Set orders
        for (int orderIndex : orders) {
            individual.genome.set(orderIndex, true);
        }

        // Set aisles
        for (int aisleIndex : aisles) {
            individual.genome.set(this.orders.size() + aisleIndex, true);
        }

        // Update fitness
        individual.fitness = computeObjectiveFunction(new ChallengeSolution(orders, aisles));
    }

    /**
     * Removes unnecessary aisles without removing any orders.
     * @param individual The individual to optimize
     * @return Optimized individual
     */
    public Individual removeUnusedAisles(Individual individual) {
        ChallengeSolution solution = decodeIndividual(individual);
        Set<Integer> selectedOrders = new HashSet<>(solution.orders());

        // Find minimum required aisles for current orders
        Set<Integer> minimalAisles = findMinimumRequiredAisles(selectedOrders);

        // Update genome with optimized aisles
        updateGenome(individual, selectedOrders, minimalAisles);

        return individual;
    }

    /**
     * Adds as many new orders as possible without adding new aisles.
     * @param individual The individual to enhance
     * @return Enhanced individual
     */
    public Individual addOrdersWithoutNewAisles(Individual individual) {
        ChallengeSolution solution = decodeIndividual(individual);
        Set<Integer> selectedOrders = new HashSet<>(solution.orders());
        Set<Integer> selectedAisles = new HashSet<>(solution.aisles());

        // Calculate currently used items and total units
        Map<Integer, Integer> usedItems = new ConcurrentHashMap<>();
        final int[] totalUnits = {0}; // Array to allow modification in lambda

        selectedOrders.parallelStream().forEach(orderIndex -> {
            Map<Integer, Integer> order = orders.get(orderIndex);
            synchronized(usedItems) {
                for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                    usedItems.put(entry.getKey(),
                            usedItems.getOrDefault(entry.getKey(), 0) + entry.getValue());
                    totalUnits[0] += entry.getValue();
                }
            }
        });

        // Calculate available items from selected aisles
        Map<Integer, Integer> availableItems = new ConcurrentHashMap<>();

        Map<Integer, Integer> finalAvailableItems = availableItems;
        selectedAisles.parallelStream().forEach(aisleIndex -> {
            Map<Integer, Integer> aisle = aisles.get(aisleIndex);
            synchronized(finalAvailableItems) {
                aisle.forEach((item, quantity) ->
                        finalAvailableItems.put(item, finalAvailableItems.getOrDefault(item, 0) + quantity));
            }
        });

        // Subtract used items to get remaining availability
        for (Map.Entry<Integer, Integer> entry : usedItems.entrySet()) {
            availableItems.put(entry.getKey(),
                    availableItems.getOrDefault(entry.getKey(), 0) - entry.getValue());
        }

        // Find candidate orders
        List<Integer> candidateOrders = IntStream.range(0, orders.size())
                .filter(i -> !selectedOrders.contains(i))
                .boxed()
                .collect(Collectors.toList());

        // Shuffle to avoid bias
        Collections.shuffle(candidateOrders);

        // Try to add orders that can be fulfilled with current resources
        boolean added;
        do {
            added = false;

            for (Iterator<Integer> it = candidateOrders.iterator(); it.hasNext();) {
                int orderIndex = it.next();
                Map<Integer, Integer> order = orders.get(orderIndex);

                // Check if order can be fulfilled with available items
                boolean canAdd = true;
                int orderSize = 0;
                Map<Integer, Integer> tempAvailable = new HashMap<>(availableItems);

                for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
                    int item = entry.getKey();
                    int needed = entry.getValue();
                    orderSize += needed;

                    if (tempAvailable.getOrDefault(item, 0) < needed) {
                        canAdd = false;
                        break;
                    }

                    tempAvailable.put(item, tempAvailable.get(item) - needed);
                }

                // Add if possible and within wave size bounds
                if (canAdd && totalUnits[0] + orderSize <= waveSizeUB) {
                    selectedOrders.add(orderIndex);
                    totalUnits[0] += orderSize;
                    availableItems = tempAvailable;
                    it.remove();
                    added = true;
                }
            }
        } while (added && totalUnits[0] < waveSizeUB);

        // Update the individual
        updateGenome(individual, selectedOrders, selectedAisles);

        return individual;
    }

    /**
     * An improved mutation method that combines different mutation strategies
     * to avoid local maxima in the search space.
     */
    public void improvedMutate(Individual individual) {
        Random rand = new Random();
        double r = rand.nextDouble();

        if (r < 0.1) {
            // 10% chance: Traditional bit-flip mutation
            int numMutations = Math.max(1, individual.genome.size() / 10);
            Set<Integer> mutationPoints = new HashSet<>();

            while (mutationPoints.size() < numMutations) {
                mutationPoints.add(rand.nextInt(individual.genome.size()));
            }

            for (int mutationPoint : mutationPoints) {
                individual.genome.set(mutationPoint, !individual.genome.get(mutationPoint));
            }
        }
        else if (r < 0.4) {
            // 30% chance: Remove some orders and their aisles
            mutateRemovePercentageOfOrders(individual, 0.3); // Remove 30% of orders
        }
        else if (r < 0.7) {
            // 30% chance: Just remove unused aisles
            removeUnusedAisles(individual);
        }
        else {
            // 30% chance: Add more orders without adding aisles
            addOrdersWithoutNewAisles(individual);
        }
    }

    /*
     * Recieves an already sorted population
     * Returns a population of size n * 1.5
     */
    public ArrayList<Individual> crossOverPopulation(ArrayList<Individual> population) {
        ArrayList<Individual> newPopulation = new ArrayList<>();
        Random rand = new Random();
        while (!population.isEmpty()) {
            Individual parent1 = population.remove(0);
            Individual parent2;
            newPopulation.add(parent1);
            //biased for loop
            if (population.isEmpty()) break;
            for (int i = 0; i < population.size(); i++) {
                if (rand.nextDouble() < 0.5 || i == population.size() - 1) {
                    parent2 = population.remove(i);
                    newPopulation.add(parent2);
                    newPopulation.add(crossover(parent1, parent2));
                }
            }
        }
        return newPopulation;

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
        if (totalUnits < waveSizeLB) {
            return false;
        }
        if (totalUnits > waveSizeUB) {

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
    public ArrayList<Individual> improvedSelection(ArrayList<Individual> population, int populationSize) {
        // Default parameters: 20% elitism, 10% diversity, 70% tournament
        return improvedSelection(population, populationSize, 0.2, 0.1);
    }

    public Individual geneticAlgorithmWithImprovedSelection(int populationSize, int maxIterations, StopWatch stopWatch) {
        // Generate initial population
        ArrayList<Individual> population = this.generateInitialPopulation(populationSize);

        // Evaluate initial population
        population.parallelStream().forEach(individual -> {
            ChallengeSolution solution = this.decodeIndividual(individual);
            individual.fitness = this.computeObjectiveFunction(solution);
        });

        population.sort((a, b) -> Double.compare(b.fitness, a.fitness));

        Individual bestIndividual = population.get(0);
        double bestFitness = bestIndividual.fitness;

        // Track average diversity for adaptive selection
        double averageDiversity = 0.0;
        int diversityMeasurements = 0;

        // Main loop

        int iterationsWithoutImprovement = 0;

        while (stopWatch.getDuration().toMillis() < MAX_RUNTIME) {

            // Crossover
            population = crossOverPopulation(population);

            // Evaluate new population
            population.parallelStream().forEach(individual -> {
                ChallengeSolution solution = this.decodeIndividual(individual);
                individual.fitness = this.computeObjectiveFunction(solution);
            });

            population.sort((a, b) -> Double.compare(b.fitness, a.fitness));

            // Calculate diversity for adaptive selection
            double currentDiversity = calculatePopulationDiversity(population);
            averageDiversity = (averageDiversity * diversityMeasurements + currentDiversity)
                    / (diversityMeasurements + 1);
            diversityMeasurements++;

            // Selection - use adaptive selection if we're stuck in a local optimum
            if (iterationsWithoutImprovement > 10) {
                population = adaptiveSelection(population, populationSize, averageDiversity);
            } else {
                population = improvedSelection(population, populationSize);
            }

            // Check if we've improved
            if (population.get(0).fitness > bestFitness) {
                bestIndividual = population.get(0);
                bestFitness = bestIndividual.fitness;
                iterationsWithoutImprovement = 0;
            } else {
                iterationsWithoutImprovement++;
            }
        }

        return bestIndividual;
    }

    protected double computeObjectiveFunction(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }

        int penalty = 0;
        if (!isSolutionFeasible(challengeSolution)) {
            penalty -= applyPenalty(challengeSolution);
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
        return (double) totalUnitsPicked / numVisitedAisles + penalty;
    }

    protected int applyPenalty(ChallengeSolution challengeSolution) {
        int penalty = 0;
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();

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
        if (totalUnits < waveSizeLB) {
            penalty += (waveSizeLB - totalUnits) * nItems;
        }

        if (totalUnits > waveSizeUB) {
            penalty += (totalUnits - waveSizeLB) * nItems;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                penalty += (totalUnitsPicked[i] - totalUnitsAvailable[i]) * nItems;
            }
        }

        return penalty;

    }

    /**
     * Adaptive selection that adjusts elitism and diversity rates based on
     * convergence detection to help escape local optima.
     */
    public ArrayList<Individual> adaptiveSelection(ArrayList<Individual> population,
                                                   int populationSize,
                                                   double averageDiversityIndex) {
        // Sort population by fitness if not already sorted
        population.sort((a, b) -> Double.compare(b.fitness, a.fitness));

        // Calculate population diversity
        double diversityMeasure = calculatePopulationDiversity(population);

        // Adjust elitism and diversity rates based on convergence detection
        double elitismRate, diversityRate;

        if (diversityMeasure < averageDiversityIndex * 0.5) {
            // Low diversity - population might be converging to local optimum
            // Decrease elitism, increase diversity
            elitismRate = 0.1;
            diversityRate = 0.3;
        } else if (diversityMeasure < averageDiversityIndex * 0.8) {
            // Moderate diversity - balanced approach
            elitismRate = 0.2;
            diversityRate = 0.15;
        } else {
            // High diversity - focus on exploiting good solutions
            elitismRate = 0.3;
            diversityRate = 0.05;
        }

        return improvedSelection(population, populationSize, elitismRate, diversityRate);
    }

    /**
     * Calculate population diversity using average Hamming distance between genomes.
     * Returns a value between 0.0 (all identical) and 1.0 (maximum diversity).
     */
    private double calculatePopulationDiversity(ArrayList<Individual> population) {
        if (population.isEmpty() || population.size() == 1) {
            return 0.0;
        }

        int genomeLength = population.get(0).genome.size();
        long totalDifferences = 0;
        long totalComparisons = 0;

        // Use parallel stream for better performance
        totalDifferences = population.parallelStream()
                .flatMap(ind1 -> population.stream()
                        .filter(ind2 -> ind1 != ind2)
                        .map(ind2 -> {
                            int differences = 0;
                            for (int i = 0; i < genomeLength; i++) {
                                if (ind1.genome.get(i) != ind2.genome.get(i)) {
                                    differences++;
                                }
                            }
                            return differences;
                        })
                )
                .mapToLong(Long::valueOf)
                .sum();

        totalComparisons = (long) population.size() * (population.size() - 1) * genomeLength;

        return (double) totalDifferences / totalComparisons;
    }

    /**
     * Update the main genetic algorithm to use the improved selection
     */
    public ArrayList<Individual> improvedSelection(ArrayList<Individual> population,
                                                   int populationSize,
                                                   double elitismRate,
                                                   double diversityRate) {
        // Validate input parameters
        if (elitismRate + diversityRate > 1.0) {
            throw new IllegalArgumentException("The sum of elitismRate and diversityRate must be <= 1.0");
        }

        // Sort population by fitness if not already sorted
        population.sort((a, b) -> Double.compare(b.fitness, a.fitness));

        // Calculate counts for each selection method
        int eliteCount = (int)(populationSize * elitismRate);
        int diversityCount = (int)(populationSize * diversityRate);
        int tournamentCount = populationSize - eliteCount - diversityCount;

        ArrayList<Individual> nextGeneration = new ArrayList<>(populationSize);
        Random rand = new Random();

        // 1. Elitism: Add the best individuals
        for (int i = 0; i < Math.min(eliteCount, population.size()); i++) {
            nextGeneration.add(population.get(i));
        }

        // 2. Diversity: Add random individuals, weighted toward "bad" solutions
        if (diversityCount > 0 && population.size() > eliteCount) {
            // Create a copy of remaining population (excluding elite individuals)
            ArrayList<Individual> remainingPop = new ArrayList<>(
                    population.subList(eliteCount, population.size())
            );

            // Calculate selection weights - higher weights for worse solutions
            // Use reverse ranking to give more weight to worse solutions
            double[] weights = new double[remainingPop.size()];
            double weightSum = 0;
            for (int i = 0; i < weights.length; i++) {
                // Assign weight based on reverse rank (worse solutions get higher weight)
                weights[i] = (weights.length - i) * (weights.length - i);
                weightSum += weights[i];
            }

            // Normalize weights to probabilities
            for (int i = 0; i < weights.length; i++) {
                weights[i] /= weightSum;
            }

            // Convert to cumulative distribution
            for (int i = 1; i < weights.length; i++) {
                weights[i] += weights[i-1];
            }

            // Use weights to select individuals with bias toward worse solutions
            for (int i = 0; i < diversityCount; i++) {
                double r = rand.nextDouble();
                // Binary search to find the index
                int index = 0;
                while (index < weights.length - 1 && weights[index] < r) {
                    index++;
                }

                if (index < remainingPop.size()) {
                    nextGeneration.add(remainingPop.get(index));
                    // Remove to avoid duplicates
                    remainingPop.remove(index);

                    // Recalculate weights if needed
                    if (i < diversityCount - 1 && !remainingPop.isEmpty()) {
                        // Only recalculate if we need to select more individuals
                        weights = new double[remainingPop.size()];
                        weightSum = 0;
                        for (int j = 0; j < weights.length; j++) {
                            weights[j] = (weights.length - j) * (weights.length - j);
                            weightSum += weights[j];
                        }
                        for (int j = 0; j < weights.length; j++) {
                            weights[j] /= weightSum;
                        }
                        for (int j = 1; j < weights.length; j++) {
                            weights[j] += weights[j-1];
                        }
                    }
                }
            }
        }

        // 3. Tournament Selection for remaining slots
        if (tournamentCount > 0 && population.size() > eliteCount + diversityCount) {
            // Filter out individuals already selected
            Set<Individual> selectedSet = new HashSet<>(nextGeneration);
            ArrayList<Individual> candidates = new ArrayList<>();

            for (Individual ind : population) {
                if (!selectedSet.contains(ind)) {
                    candidates.add(ind);
                }
            }

            // Fill remaining slots with tournament selection
            int tournamentSize = Math.max(2, Math.min(5, candidates.size() / 10));

            for (int i = 0; i < tournamentCount; i++) {
                if (candidates.isEmpty()) break;

                // Select individuals for tournament
                ArrayList<Individual> tournament = new ArrayList<>(tournamentSize);
                for (int j = 0; j < tournamentSize; j++) {
                    if (candidates.isEmpty()) break;
                    int index = rand.nextInt(candidates.size());
                    tournament.add(candidates.get(index));
                }

                // Find winner
                Individual winner = tournament.stream()
                        .max(Comparator.comparingDouble(ind -> ind.fitness))
                        .orElse(null);

                if (winner != null) {
                    nextGeneration.add(winner);
                    candidates.remove(winner);
                }
            }
        }

        // If we couldn't find enough individuals using the above methods,
        // clone some of the elite individuals
        while (nextGeneration.size() < populationSize && !nextGeneration.isEmpty()) {
            // Clone a random elite individual
            int index = rand.nextInt(Math.min(eliteCount, nextGeneration.size()));
            Individual elite = nextGeneration.get(index);

            // Create a clone with small random variations
            ArrayList<Boolean> newGenome = new ArrayList<>(elite.genome);

            // Apply minor mutations to differentiate the clone
            int numMutations = Math.max(1, newGenome.size() / 20); // 5% mutation rate
            for (int i = 0; i < numMutations; i++) {
                int pos = rand.nextInt(newGenome.size());
                newGenome.set(pos, !newGenome.get(pos));
            }

            Individual clone = new Individual(newGenome, -1); // Fitness will be calculated later
            nextGeneration.add(clone);
        }

        return nextGeneration;
    }
}