package org.sbpo2025.challenge;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.time.StopWatch;

public class ChallengeSolver {
    private final List<Map<Integer, Integer>> orders;
    private final List<Map<Integer, Integer>> aisles;
    private final int nItems;
    private final int waveSizeLB;
    private final int waveSizeUB;

    public ChallengeSolver(List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, 
                          int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    // Flag para interromper o algoritmo
    private static final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    // Monitor para interrupção por teclado
    private static class KeyboardMonitor extends Thread {
        @Override
        public void run() {
            Scanner scanner = new Scanner(System.in);
            System.out.println("Pressione ENTER a qualquer momento para interromper e obter o melhor resultado atual.");
            scanner.nextLine();
            shouldStop.set(true);
            System.out.println("Interrupção solicitada. Finalizando e retornando o melhor resultado...");
        }
    }

    // Classe que representa um Pedido
    static class Order {
        final int id;
        // item -> quantidade demandada
        final Map<Integer, Integer> itemDemand;
        int totalDemand;
        
        public Order(int id) {
            this.id = id;
            this.itemDemand = new HashMap<>();
            this.totalDemand = 0;
        }
        
        public void addItem(int item, int qty) {
            itemDemand.put(item, qty);
            totalDemand += qty;
        }
    }

    // Classe que representa um Corredor
    static class Corridor {
        final int id;
        // item -> quantidade disponível
        final Map<Integer, Integer> itemAvailability;
        
        public Corridor(int id) {
            this.id = id;
            this.itemAvailability = new HashMap<>();
        }
        
        public void addItem(int item, int qty) {
            itemAvailability.put(item, qty);
        }
    }

    // Classe que representa uma solução (wave)
    static class Solution {
        Set<Order> orders;           // Conjunto de pedidos selecionados
        Set<Corridor> corridors;     // Conjunto de corredores selecionados para cobrir a demanda
        int totalUnits;              // Soma das unidades de todos os pedidos
        double objective;            // Valor objetivo = totalUnits / número de corredores
        Map<Integer, Integer> aggregatedDemand; // Cache da demanda agregada

        public Solution() {
            orders = new HashSet<>();
            corridors = new HashSet<>();
            totalUnits = 0;
            objective = 0.0;
            aggregatedDemand = null;
        }
        
        // Cria uma cópia profunda da solução
        public Solution copy() {
            Solution copy = new Solution();
            copy.orders = new HashSet<>(this.orders);
            copy.corridors = new HashSet<>(this.corridors);
            copy.totalUnits = this.totalUnits;
            copy.objective = this.objective;
            return copy;
        }
        
        // Atualiza a soma total de unidades a partir dos pedidos
        public void updateTotalUnits() {
            totalUnits = 0;
            for (Order o : orders) {
                totalUnits += o.totalDemand;
            }
        }
        
        // Calcula o valor objetivo se houver pelo menos um corredor
        public void computeObjective() {
            objective = corridors.isEmpty() ? 0.0 : (double) totalUnits / corridors.size();
        }
        
        // Agrega a demanda de todos os pedidos: retorna um mapa item -> demanda total
        public Map<Integer, Integer> getAggregatedDemand() {
            if (aggregatedDemand != null) {
                return aggregatedDemand;
            }
            
            aggregatedDemand = new HashMap<>();
            for (Order o : orders) {
                for (Map.Entry<Integer, Integer> entry : o.itemDemand.entrySet()) {
                    aggregatedDemand.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
            return aggregatedDemand;
        }
        
        // Invalida o cache quando a coleção de pedidos é modificada
        public void invalidateCache() {
            aggregatedDemand = null;
        }
        
        // Retorna um resumo da solução atual
        @Override
        public String toString() {
            return String.format("Solução: %d pedidos, %d corredores, %d unidades, objetivo: %.2f", 
                orders.size(), corridors.size(), totalUnits, objective);
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length < 2){
            System.err.println("Uso: java ChallengeSolver <input_file> <output_file>");
            return;
        }
        String inputFile = args[0];
        String outputFile = args[1];

        // Inicia o monitor de teclado em uma thread separada
        KeyboardMonitor monitor = new KeyboardMonitor();
        monitor.setDaemon(true);
        monitor.start();

        try (Scanner sc = new Scanner(new File(inputFile))) {
            // Leitura da primeira linha: número de pedidos, itens e corredores
            int numOrders = sc.nextInt();
            int numItems = sc.nextInt();
            int numCorridors = sc.nextInt();
            
            System.out.println("Lendo " + numOrders + " pedidos, " + numItems + " itens, " + numCorridors + " corredores");
            
            List<Order> orders = new ArrayList<>(numOrders);
            // Leitura dos pedidos
            for (int o = 0; o < numOrders; o++) {
                Order order = new Order(o);
                int k = sc.nextInt();
                for (int j = 0; j < k; j++) {
                    int item = sc.nextInt();
                    int qty = sc.nextInt();
                    order.addItem(item, qty);
                }
                orders.add(order);
            }
            
            List<Corridor> corridors = new ArrayList<>(numCorridors);
            // Leitura dos corredores
            for (int a = 0; a < numCorridors; a++) {
                Corridor corridor = new Corridor(a);
                int l = sc.nextInt();
                for (int j = 0; j < l; j++) {
                    int item = sc.nextInt();
                    int qty = sc.nextInt();
                    corridor.addItem(item, qty);
                }
                corridors.add(corridor);
            }
            
            // Leitura dos limites LB e UB
            int LB = sc.nextInt();
            int UB = sc.nextInt();
            
            System.out.println("Limites: LB=" + LB + ", UB=" + UB);
            System.out.println("Iniciando otimização...");
            
            // Executa a metaheurística
            ChallengeSolution bestSolution = solve(orders, corridors, LB, UB);
            
            // Grava a solução no arquivo de saída no formato especificado
            writeSolution(bestSolution, outputFile);
            System.out.println("Solução final: " + bestSolution);
        }
    }
    
    // Função para gravar a solução no arquivo de saída
    static void writeSolution(ChallengeSolution solution, String outputFile) throws Exception {
        try (PrintWriter pw = new PrintWriter(outputFile)) {
            // Primeira linha: número de pedidos na wave
            pw.println(solution.orders().size());
            // Lista de pedidos (índices)
            List<Integer> orderIds = new ArrayList<>(solution.orders());
            Collections.sort(orderIds);
            for (int id : orderIds)
                pw.println(id);
            // Linha com número de corredores
            pw.println(solution.aisles().size());
            List<Integer> corridorIds = new ArrayList<>(solution.aisles());
            Collections.sort(corridorIds);
            for (int id : corridorIds)
                pw.println(id);
        }
        System.out.println("Solução gravada em " + outputFile);
    }
    
    // Função principal da metaheurística
    static ChallengeSolution solve(List<Order> orders, List<Corridor> corridors, int LB, int UB) {
        long startTime = System.currentTimeMillis();
        // Tempo limite em milissegundos (9 minutos para segurança)
        long TIME_LIMIT = 570000;
        // Intervalo para imprimir progresso (15 segundos)
        long PROGRESS_INTERVAL = 15000;
        long lastProgressTime = startTime;
        
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        Solution bestSolution = null;
        int iterations = 0;
        
        // Pré-ordena os pedidos por densidade de valor para possivelmente iniciar com boas soluções
        List<Order> sortedOrders = new ArrayList<>(orders);
        sortedOrders.sort(Comparator.comparingInt(o -> -o.totalDemand));
        
        // Matriz de corredores por itens para acesso mais rápido
        Map<Integer, Set<Corridor>> itemToCorridors = new HashMap<>();
        for (Corridor c : corridors) {
            for (int item : c.itemAvailability.keySet()) {
                itemToCorridors.computeIfAbsent(item, k -> new HashSet<>()).add(c);
            }
        }
        
        while(System.currentTimeMillis() - startTime < TIME_LIMIT && !shouldStop.get()) {
            iterations++;
            
            // Fase de construção: tenta alternar entre abordagem gulosa e aleatória
            Solution candidate;
            if (rand.nextBoolean()) {
                candidate = generateGreedyCandidate(sortedOrders, LB, UB, rand);
            } else {
                candidate = generateRandomCandidate(orders, LB, UB, rand);
            }
            
            if(candidate == null) continue; // se não conseguiu construir uma solução viável
            
            // Atribuição dos corredores (subproblema de cobertura)
            Set<Corridor> assigned = assignCorridors(candidate.getAggregatedDemand(), corridors, itemToCorridors);
            if(assigned == null) continue; // não encontrou cobertura viável
            candidate.corridors = assigned;
            candidate.computeObjective();
            
            // Fase de refinamento: busca local
            candidate = localSearch(candidate, corridors);
            
            // Atualiza a melhor solução encontrada
            if(bestSolution == null || candidate.objective > bestSolution.objective) {
                bestSolution = candidate.copy();
                System.out.println("Nova melhor solução encontrada! " + bestSolution);
            }
            
            // Imprime progresso periodicamente
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastProgressTime > PROGRESS_INTERVAL) {
                lastProgressTime = currentTime;
                double elapsedSeconds = (currentTime - startTime) / 1000.0;
                System.out.printf("%.1f segundos decorridos, %d iterações, melhor objetivo: %.2f%n", 
                                  elapsedSeconds, iterations, 
                                  bestSolution != null ? bestSolution.objective : 0.0);
            }
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.printf("Otimização concluída em %.1f segundos após %d iterações.%n", 
                          totalTime / 1000.0, iterations);
        
        if (shouldStop.get()) {
            System.out.println("Algoritmo interrompido pelo usuário.");
        } else {
            System.out.println("Tempo limite atingido.");
        }
        
        // Converter a solução para o formato ChallengeSolution
        if (bestSolution == null) {
            return new ChallengeSolution(Set.of(), Set.of());
        }
        
        Set<Integer> orderIds = new HashSet<>();
        for (Order o : bestSolution.orders) {
            orderIds.add(o.id);
        }
        
        Set<Integer> corridorIds = new HashSet<>();
        for (Corridor c : bestSolution.corridors) {
            corridorIds.add(c.id);
        }
        
        return new ChallengeSolution(orderIds, corridorIds);
    }
    
    // Gera uma solução candidata selecionando pedidos de forma gulosa
    static Solution generateGreedyCandidate(List<Order> sortedOrders, int LB, int UB, ThreadLocalRandom rand) {
        Solution sol = new Solution();
        
        // Começa com uma parte aleatória dos pedidos já ordenados
        int startIdx = rand.nextInt(Math.min(10, sortedOrders.size()));
        for (int i = startIdx; i < sortedOrders.size() && sol.totalUnits < UB; i++) {
            Order o = sortedOrders.get(i);
            if (sol.totalUnits + o.totalDemand <= UB) {
                sol.orders.add(o);
                sol.totalUnits += o.totalDemand;
                sol.invalidateCache();
            }
        }
        
        // Verifica se a solução candidata atende o LB
        if (sol.totalUnits < LB) return null;
        return sol;
    }
    
    // Gera uma solução candidata selecionando pedidos de forma aleatorizada
    static Solution generateRandomCandidate(List<Order> orders, int LB, int UB, ThreadLocalRandom rand) {
        // Cria uma cópia aleatória da lista de pedidos para diversificação
        List<Order> shuffled = new ArrayList<>(orders);
        Collections.shuffle(shuffled, rand);
        
        Solution sol = new Solution();
        for(Order o : shuffled) {
            // Tenta adicionar o pedido se não ultrapassar UB
            if(sol.totalUnits + o.totalDemand <= UB) {
                sol.orders.add(o);
                sol.totalUnits += o.totalDemand;
                sol.invalidateCache();
                // Se já atingiu o LB, pode interromper a seleção
                if(sol.totalUnits >= LB)
                    break;
            }
        }
        // Verifica se a solução candidata atende o LB
        if(sol.totalUnits < LB) return null;
        return sol;
    }
    
    // Atribui corredores de forma gulosa para cobrir a demanda agregada dos pedidos
    static Set<Corridor> assignCorridors(Map<Integer, Integer> demand, List<Corridor> corridors, 
                                        Map<Integer, Set<Corridor>> itemToCorridors) {
        // unsatisfied: demanda remanescente para cada item
        Map<Integer, Integer> unsatisfied = new HashMap<>(demand);
        Set<Corridor> selected = new HashSet<>();
        Set<Integer> unsatisfiedItems = new HashSet<>(unsatisfied.keySet());
        
        while(!unsatisfiedItems.isEmpty()) {
            Corridor bestCorridor = null;
            double bestRatio = 0;
            int bestContribution = 0;
            
            // Considerar apenas corredores que têm itens insatisfeitos
            Set<Corridor> candidateCorridors = new HashSet<>();
            for (int item : unsatisfiedItems) {
                Set<Corridor> corridorsWithItem = itemToCorridors.getOrDefault(item, Collections.emptySet());
                candidateCorridors.addAll(corridorsWithItem);
            }
            
            // Remove corredores já selecionados
            candidateCorridors.removeAll(selected);
            
            for (Corridor c : candidateCorridors) {
                int contribution = 0;
                int itemCount = 0;
                
                for (Map.Entry<Integer, Integer> entry : c.itemAvailability.entrySet()) {
                    int item = entry.getKey();
                    int available = entry.getValue();
                    int rem = unsatisfied.getOrDefault(item, 0);
                    if (rem > 0) {
                        contribution += Math.min(available, rem);
                        itemCount++;
                    }
                }
                
                // Calcula um ratio que prioriza corredores que cobrem mais itens diferentes
                double ratio = itemCount > 0 ? (double) contribution / itemCount : 0;
                
                if (contribution > 0 && (bestCorridor == null || ratio > bestRatio || 
                    (ratio == bestRatio && contribution > bestContribution))) {
                    bestContribution = contribution;
                    bestRatio = ratio;
                    bestCorridor = c;
                }
            }
            
            if (bestCorridor == null) {
                // Não conseguimos encontrar mais corredores que possam ajudar
                return null;
            }
            
            selected.add(bestCorridor);
            
            // Atualiza a demanda remanescente
            boolean allSatisfied = true;
            for (Map.Entry<Integer, Integer> entry : bestCorridor.itemAvailability.entrySet()) {
                int item = entry.getKey();
                int avail = entry.getValue();
                int rem = unsatisfied.getOrDefault(item, 0);
                
                if (rem > 0) {
                    int newRem = Math.max(0, rem - avail);
                    unsatisfied.put(item, newRem);
                    
                    if (newRem == 0) {
                        unsatisfiedItems.remove(item);
                    } else {
                        allSatisfied = false;
                    }
                }
            }
            
            if (allSatisfied && unsatisfiedItems.isEmpty()) {
                break;
            }
        }
        
        // Se alguma demanda ainda não foi satisfeita, não há cobertura viável
        return unsatisfiedItems.isEmpty() ? selected : null;
    }
    
    // Busca local para tentar reduzir o número de corredores
    static Solution localSearch(Solution sol, List<Corridor> corridors) {
        boolean improved = true;
        Map<Integer, Integer> demand = sol.getAggregatedDemand();
        
        while(improved) {
            improved = false;
            
            // Tenta corredores para remover em ordem de menor contribuição
            List<Corridor> sortedCorridors = new ArrayList<>(sol.corridors);
            sortedCorridors.sort((c1, c2) -> {
                int contribution1 = calculateContribution(c1, demand);
                int contribution2 = calculateContribution(c2, demand);
                return Integer.compare(contribution1, contribution2);
            });
            
            for (Corridor c : sortedCorridors) {
                Set<Corridor> testSet = new HashSet<>(sol.corridors);
                testSet.remove(c);
                
                // Verifica se o conjunto de corredores restantes cobre a demanda
                if (checkCoverage(demand, testSet)) {
                    sol.corridors.remove(c);
                    improved = true;
                    break;  // Reinicia o processo após remover um corredor
                }
            }
        }
        
        sol.computeObjective();
        return sol;
    }
    
    // Calcula a contribuição de um corredor para a demanda
    static int calculateContribution(Corridor c, Map<Integer, Integer> demand) {
        int contribution = 0;
        for (Map.Entry<Integer, Integer> entry : c.itemAvailability.entrySet()) {
            int item = entry.getKey();
            int available = entry.getValue();
            int required = demand.getOrDefault(item, 0);
            contribution += Math.min(available, required);
        }
        return contribution;
    }
    
    // Verifica se o conjunto de corredores cobre a demanda agregada
    static boolean checkCoverage(Map<Integer, Integer> demand, Set<Corridor> selected) {
        if (selected.isEmpty() && !demand.isEmpty()) {
            return false;
        }
        
        Map<Integer, Integer> covered = new HashMap<>();
        // Soma as disponibilidades de cada corredor selecionado
        for (Corridor c : selected) {
            for (Map.Entry<Integer, Integer> entry : c.itemAvailability.entrySet()) {
                int item = entry.getKey();
                int avail = entry.getValue();
                covered.merge(item, avail, Integer::sum);
            }
        }
        
        // Verifica se a cobertura é suficiente para cada item na demanda
        for (Map.Entry<Integer, Integer> entry : demand.entrySet()) {
            int item = entry.getKey();
            int required = entry.getValue();
            if(covered.getOrDefault(item, 0) < required)
                return false;
        }
        return true;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        // Converter os pedidos para o formato Order
        List<Order> internalOrders = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = new Order(i);
            for (Map.Entry<Integer, Integer> entry : orders.get(i).entrySet()) {
                order.addItem(entry.getKey(), entry.getValue());
            }
            internalOrders.add(order);
        }
        
        // Converter os corredores para o formato Corridor
        List<Corridor> internalCorridors = new ArrayList<>();
        for (int i = 0; i < aisles.size(); i++) {
            Corridor corridor = new Corridor(i);
            for (Map.Entry<Integer, Integer> entry : aisles.get(i).entrySet()) {
                corridor.addItem(entry.getKey(), entry.getValue());
            }
            internalCorridors.add(corridor);
        }
        
        return solve(internalOrders, internalCorridors, waveSizeLB, waveSizeUB);
    }
}