import os
import time
from multiprocessing import Pool
import gurobipy as gp
from gurobipy import GRB

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

# Função para escrever a saída no formato esperado pelo checker
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
    
    # Criar modelo Gurobi
    model = gp.Model(f"WavePicking_{instance_file}")
    model.setParam('OutputFlag', 0)  # Silenciar saída detalhada do Gurobi
    
    # Variáveis
    x = model.addVars(len(orders), vtype=GRB.BINARY, name="x")
    y = model.addVars(len(aisles), vtype=GRB.BINARY, name="y")
    
    # Restrições de tamanho da wave
    model.addConstr(gp.quicksum(total_units[o] * x[o] for o in range(len(orders))) >= wave_size_lb, "WaveLB")
    model.addConstr(gp.quicksum(total_units[o] * x[o] for o in range(len(orders))) <= wave_size_ub, "WaveUB")
    
    # Restrições de disponibilidade
    for i in range(n_items):
        if item_to_orders[i]:  # Apenas itens que aparecem em algum pedido
            lhs = gp.quicksum(u_oi * x[o] for o, u_oi in item_to_orders[i])
            rhs = gp.quicksum(u_ai * y[a] for a, u_ai in item_to_aisles[i])
            model.addConstr(lhs <= rhs, f"Availability_{i}")
    
    # Restrição de número de corredores (será ajustada para cada k)
    aisle_constr = model.addConstr(gp.quicksum(y[a] for a in range(len(aisles))) <= 1, "AisleLimit")
    
    # Objetivo
    obj = gp.quicksum(total_units[o] * x[o] for o in range(len(orders)))
    model.setObjective(obj, GRB.MAXIMIZE)
    
    # Resolver para diferentes valores de k
    best_ratio = 0
    best_solution = None
    k_max = len(aisles)  # Máximo é o número total de corredores
    
    for k in range(1, k_max + 1):
        # Ajustar o limite de corredores
        aisle_constr.setAttr(GRB.Attr.RHS, k)
        
        # Otimizar
        model.optimize()
        
        if model.status == GRB.OPTIMAL:
            v_k = model.ObjVal
            ratio = v_k / k
            elapsed_time = time.time() - start_time
            
            # Exibir progresso
            print(f"Instância {instance_file} | k={k} | Total unidades={v_k:.2f} | Razão={ratio:.2f} | "
                  f"Tempo={elapsed_time:.2f}s")
            
            if ratio > best_ratio:
                best_ratio = ratio
                selected_orders = [o for o in range(len(orders)) if x[o].X > 0.5]
                selected_aisles = [a for a in range(len(aisles)) if y[a].X > 0.5]
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