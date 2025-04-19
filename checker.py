import numpy as np
import os

class WaveOrderPicking:
    def __init__(self):
        self.orders = None
        self.aisles = None
        self.wave_size_lb = None
        self.wave_size_ub = None

    def read_input(self, input_file_path):
        with open(input_file_path, 'r') as file:
            lines = file.readlines()
            first_line = lines[0].strip().split()
            o, i, a = int(first_line[0]), int(first_line[1]), int(first_line[2])

            self.orders = []
            for j in range(o):
                order_line = lines[j + 1].strip().split()
                d = int(order_line[0])
                order_map = {int(order_line[2 * k + 1]): int(order_line[2 * k + 2]) for k in range(d)}
                self.orders.append(order_map)

            self.aisles = []
            for j in range(a):
                aisle_line = lines[j + o + 1].strip().split()
                d = int(aisle_line[0])
                aisle_map = {int(aisle_line[2 * k + 1]): int(aisle_line[2 * k + 2]) for k in range(d)}
                self.aisles.append(aisle_map)

            bounds = lines[o + a + 1].strip().split()
            self.wave_size_lb = int(bounds[0])
            self.wave_size_ub = int(bounds[1])

    def read_output(self, output_file_path):
        with open(output_file_path, 'r') as file:
            lines = file.readlines()
            num_orders = int(lines[0].strip())
            selected_orders = [int(lines[i + 1].strip()) for i in range(num_orders)]
            num_aisles = int(lines[num_orders + 1].strip())
            visited_aisles = [int(lines[num_orders + 2 + i].strip()) for i in range(num_aisles)]

        selected_orders = list(set(selected_orders))
        visited_aisles = list(set(visited_aisles))
        return selected_orders, visited_aisles

    def is_solution_feasible(self, selected_orders, visited_aisles):
        total_units_picked = 0
        for order in selected_orders:
            total_units_picked += np.sum(list(self.orders[order].values()))

        if not (self.wave_size_lb <= total_units_picked <= self.wave_size_ub):
            return False

        required_items = set()
        for order in selected_orders:
            required_items.update(self.orders[order].keys())

        for item in required_items:
            total_required = sum(self.orders[order].get(item, 0) for order in selected_orders)
            total_available = sum(self.aisles[aisle].get(item, 0) for aisle in visited_aisles)
            if total_required > total_available:
                return False

        return True

    def compute_objective_function(self, selected_orders, visited_aisles):
        total_units_picked = 0
        for order in selected_orders:
            total_units_picked += np.sum(list(self.orders[order].values()))
        
        num_visited_aisles = len(visited_aisles)
        return total_units_picked / num_visited_aisles

def check_all_files(input_dir="datasets/b", output_dir="output"):
    wave_order_picking = WaveOrderPicking()
    
    # Obter lista de arquivos de entrada e saída
    input_files = [f for f in os.listdir(input_dir) if f.endswith('.txt')]
    output_files = [f for f in os.listdir(output_dir) if f.endswith('.txt')]
    
    # Para cada par correspondente
    for input_file in input_files:
        input_base = os.path.splitext(input_file)[0]
        matching_output = f"{input_base}.txt"
        
        if matching_output in output_files:
            input_path = os.path.join(input_dir, input_file)
            output_path = os.path.join(output_dir, matching_output)
            
            print(f"\nVerificando par: {input_file} e {matching_output}")
            
            try:
                # Ler entrada e saída
                wave_order_picking.read_input(input_path)
                selected_orders, visited_aisles = wave_order_picking.read_output(output_path)
                
                # Verificar factibilidade e objetivo
                is_feasible = wave_order_picking.is_solution_feasible(selected_orders, visited_aisles)
                print(f"É factível: {is_feasible}")
                
                if is_feasible:
                    objective_value = wave_order_picking.compute_objective_function(selected_orders, visited_aisles)
                    print(f"Valor da função objetivo: {objective_value:.2f}")
                    
            except ZeroDivisionError:
                print("Erro: Divisão por zero - Nenhum corredor visitado na solução.")
            except Exception as e:
                print(f"Erro ao processar o par: {str(e)}")
        else:
            print(f"\nAviso: Nenhum arquivo de saída correspondente encontrado para {input_file}")

if __name__ == "__main__":
    import sys
    if len(sys.argv) == 1:
        # Sem argumentos, verifica todos os arquivos nos diretórios especificados
        check_all_files()
    elif len(sys.argv) == 3:
        # Com dois argumentos, verifica apenas o par especificado
        wave_order_picking = WaveOrderPicking()
        wave_order_picking.read_input(sys.argv[1])
        selected_orders, visited_aisles = wave_order_picking.read_output(sys.argv[2])
        
        is_feasible = wave_order_picking.is_solution_feasible(selected_orders, visited_aisles)
        objective_value = wave_order_picking.compute_objective_function(selected_orders, visited_aisles)
        
        print("É factível:", is_feasible)
        if is_feasible:
            print("Valor da função objetivo:", objective_value)
    else:
        print("Uso: python checker.py [<input_file> <output_file>]")
        print("Sem argumentos: verifica todos os pares em /datasets/a e output/")
        sys.exit(1)