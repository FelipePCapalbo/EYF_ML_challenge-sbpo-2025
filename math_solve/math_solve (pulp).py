import os
import time
from multiprocessing import Pool
import pulp

# Função para ler uma instância
def read_instance(file_path):
    with open(file_path, 'r') as f:
        lines = f.readlines()
        first_line = lines[0].strip().split()
        n_orders, n_items, n_aisles = int(first_line[0]), int(first_line[1]), int(first_line[2])
        
        # Ler pedidos
        orders = []
        for i in range(1, n_orders + 1):
            line = lines[i].strip().split()
            d = int(line[0])
            order = {int(line[2*k+1]): int(line[2*k+2]) for k in range(d)}
            orders.append(order)
        
        # Ler corredores
        aisles = []
        for i in range(n_orders + 1, n_orders + n_aisles + 1):
            line = lines[i].strip().split()
            d = int(line[0])
            aisle = {int(line[2*k+1]): int(line[2*k+2]) for k in range(d)}
            aisles.append(aisle)
        
        # Ler limites da wave
        bounds = lines[n_orders + n_aisles + 1].strip().split()
        wave_size_lb, wave_size_ub = int(bounds[0]), int(bounds[1])
        
    return orders, aisles, n_items, wave_size_lb, wave_size_ub

# Função para escrever a saída no formato esperado
def write_output(output_file_path, selected_orders, selected_aisles):
    with open(output_file_path, 'w') as f:
        f.write(f"{len(selected_orders)}\n")
        for o in selected_orders:
            f.write(f"{o}\n")
        f.write(f"{len(selected_aisles)}\n")
        for a in selected_aisles:
            f.write(f"{a}\n")

# Função para resolver uma instância
def solve_instance(instance_file):
    start_time = time.time()
    input_path = os.path.join('datasets/a', instance_file)
    output_path = os.path.join('output', instance_file)
    
    # Ler a instância
    orders, aisles, n_items, wave_size_lb, wave_size_ub = read_instance(input_path)
    
    # Pré-computar total de unidades por pedido
    total_units = [sum(order.values()) for order in orders]
    
    # Mapear itens para pedidos e corredores
    item_to_orders = {i: [] for i in range(n_items)}
    for o, order in enumerate(orders):
        for i, qty in order.items():
            item_to_orders[i].append((o, qty))
    
    item_to_aisles = {i: [] for i in range(n_items)}
    for a, aisle in enumerate(aisles):
        for i, qty in aisle.items():
            item_to_aisles[i].append((a, qty))
    
    # Resolver para diferentes valores de k
    best_ratio = 0
    best_solution = None
    k_max = len(aisles)  # Número total de corredores
    
    for k in range(1, k_max + 1):
        # Criar modelo PuLP
        model = pulp.LpProblem(f"WavePicking_{instance_file}_k{k}", pulp.LpMaximize)
        
        # Variáveis binárias
        x = pulp.LpVariable.dicts("x", range(len(orders)), cat=pulp.LpBinary)  # Seleção de pedidos
        y = pulp.LpVariable.dicts("y", range(len(aisles)), cat=pulp.LpBinary)  # Seleção de corredores
        
        # Restrições de tamanho da wave
        model += pulp.lpSum(total_units[o] * x[o] for o in range(len(orders))) >= wave_size_lb, "WaveLB"
        model += pulp.lpSum(total_units[o] * x[o] for o in range(len(orders))) <= wave_size_ub, "WaveUB"
        
        # Restrições de disponibilidade de itens
        for i in range(n_items):
            if item_to_orders[i]:  # Apenas itens presentes em pedidos
                lhs = pulp.lpSum(u_oi * x[o] for o, u_oi in item_to_orders[i])  # Demanda
                rhs = pulp.lpSum(u_ai * y[a] for a, u_ai in item_to_aisles[i])  # Oferta
                model += lhs <= rhs, f"Availability_{i}"
        
        # Limite no número de corredores
        model += pulp.lpSum(y[a] for a in range(len(aisles))) <= k, "AisleLimit"
        
        # Objetivo: maximizar total de unidades atendidas
        obj = pulp.lpSum(total_units[o] * x[o] for o in range(len(orders)))
        model += obj, "TotalUnits"
        
        # Resolver o modelo com CBC
        model.solve(pulp.PULP_CBC_CMD(msg=0))  # Silenciar saída do solver
        
        # Verificar se a solução é ótima
        if model.status == pulp.LpStatusOptimal:
            v_k = pulp.value(model.objective)  # Valor objetivo
            ratio = v_k / k  # Razão unidades/corredores
            elapsed_time = time.time() - start_time
            
            print(f"Instância {instance_file} | k={k} | Total unidades={v_k:.2f} | Razão={ratio:.2f} | "
                  f"Tempo={elapsed_time:.2f}s")
            
            if ratio > best_ratio:
                best_ratio = ratio
                selected_orders = [o for o in range(len(orders)) if x[o].value() > 0.5]
                selected_aisles = [a for a in range(len(aisles)) if y[a].value() > 0.5]
                best_solution = (selected_orders, selected_aisles)
                print(f"** Nova melhor solução para {instance_file} | Razão={best_ratio:.2f} | "
                      f"Pedidos={len(selected_orders)} | Corredores={len(selected_aisles)} | "
                      f"Tempo={elapsed_time:.2f}s **")
        else:
            print(f"Instância {instance_file} | k={k} | Sem solução viável")
            continue
    
    # Salvar a melhor solução
    if best_solution:
        selected_orders, selected_aisles = best_solution
        write_output(output_path, selected_orders, selected_aisles)
        total_time = time.time() - start_time
        print(f"Instância {instance_file} concluída | Melhor razão={best_ratio:.2f} | "
              f"Tempo total={total_time:.2f}s")
    else:
        print(f"Instância {instance_file} | Nenhuma solução encontrada")

# Função principal
if __name__ == "__main__":
    # Criar pasta de saída, se não existir
    if not os.path.exists('output'):
        os.makedirs('output')
    
    # Listar instâncias
    instance_files = [f for f in os.listdir('datasets/a') 
                      if f.startswith('instance_') and f.endswith('.txt')]
    instance_files.sort()  # Ordenar para consistência
    
    print(f"Total de instâncias a processar: {len(instance_files)}")
    
    # Paralelizar a resolução das instâncias
    num_processes = os.cpu_count()  # Usar todos os núcleos disponíveis
    with Pool(processes=num_processes) as pool:
        pool.map(solve_instance, instance_files)
    
    print("Todas as instâncias foram processadas. Resultados salvos em 'output'.")