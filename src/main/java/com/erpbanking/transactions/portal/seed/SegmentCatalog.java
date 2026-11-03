package com.erpbanking.transactions.portal.seed;

import java.util.List;
import java.util.Map;

/** Dados de referência por segmento usados pelo gerador (produtos, fornecedores, canais, comportamento). */
final class SegmentCatalog {

    /** Produto de referência com faixa de custo unitário. */
    record Product(String nome, double custoMin, double custoMax) {}

    /** Pesos do restante (fora o crédito) entre débito, PIX, boleto e dinheiro. */
    record OtherMix(double debito, double pix, double boleto, double dinheiro) {}

    private SegmentCatalog() {}

    static final List<String> ADQUIRENTES = List.of("Cielo", "Stone", "Rede", "GetNet");

    static final List<String> BANCOS_BOLETO = List.of("001", "033", "104", "237", "341", "756");

    private static final Map<String, String> SKU_PREFIX = Map.of(
            "varejo", "VAR", "alimentacao", "ALI", "servicos", "SRV", "saude", "SAU", "tecnologia", "TEC",
            "industria", "IND", "agro", "AGR", "construcao", "CON", "outro", "GEN");

    private static final Map<String, Double> TICKET = Map.of(
            "varejo", 140.0, "alimentacao", 65.0, "servicos", 900.0, "saude", 380.0, "tecnologia", 1800.0,
            "industria", 9000.0, "agro", 14000.0, "construcao", 4500.0, "outro", 350.0);

    /** Fator por dia da semana, de segunda (0) a domingo (6); normalizado depois para média 1. */
    private static final Map<String, double[]> WEEKDAY = Map.of(
            "varejo", new double[]{0.85, 0.85, 0.9, 0.95, 1.15, 1.45, 0.85},
            "alimentacao", new double[]{0.65, 0.75, 0.85, 0.95, 1.3, 1.55, 1.3},
            "servicos", new double[]{1.15, 1.15, 1.15, 1.15, 1.1, 0.35, 0.05},
            "saude", new double[]{1.1, 1.15, 1.15, 1.15, 1.05, 0.55, 0.05},
            "tecnologia", new double[]{1.2, 1.2, 1.2, 1.15, 1.05, 0.15, 0.05},
            "industria", new double[]{1.2, 1.2, 1.2, 1.2, 1.1, 0.1, 0.0},
            "agro", new double[]{1.1, 1.05, 1.05, 1.05, 1.05, 0.75, 0.25},
            "construcao", new double[]{1.15, 1.15, 1.1, 1.1, 1.1, 0.65, 0.05},
            "outro", new double[]{1.0, 1.0, 1.0, 1.0, 1.1, 0.8, 0.5});

    private static final Map<String, OtherMix> MIX = Map.of(
            "varejo", new OtherMix(0.35, 0.45, 0.05, 0.15),
            "alimentacao", new OtherMix(0.45, 0.35, 0.0, 0.20),
            "servicos", new OtherMix(0.10, 0.50, 0.40, 0.0),
            "saude", new OtherMix(0.30, 0.50, 0.10, 0.10),
            "tecnologia", new OtherMix(0.05, 0.35, 0.60, 0.0),
            "industria", new OtherMix(0.0, 0.30, 0.70, 0.0),
            "agro", new OtherMix(0.0, 0.35, 0.65, 0.0),
            "construcao", new OtherMix(0.15, 0.40, 0.40, 0.05),
            "outro", new OtherMix(0.25, 0.45, 0.15, 0.15));

    private static final Map<String, List<String>> CANAIS = Map.of(
            "varejo", List.of("Loja física", "E-commerce próprio", "Marketplace", "Redes sociais"),
            "alimentacao", List.of("Salão", "Delivery", "Balcão"),
            "servicos", List.of("Contrato recorrente", "Avulso", "Indicação"),
            "saude", List.of("Particular", "Convênio", "Telemedicina"),
            "tecnologia", List.of("Assinatura", "Implantação", "Self-service"),
            "industria", List.of("Venda direta", "Representante", "Distribuidor"),
            "agro", List.of("Cooperativa", "Trading", "Venda direta"),
            "construcao", List.of("Obra contratada", "Balcão", "Licitação"),
            "outro", List.of("Venda direta", "Online"));

    private static final Map<String, List<String>> FORNECEDORES = Map.of(
            "varejo", List.of("Confecções Bom Retiro Ltda", "Distribuidora Moda Sul", "Calçados Franca Ind. e Com.",
                    "Têxtil Vale do Itajaí S.A.", "Atacadão dos Acessórios"),
            "alimentacao", List.of("Atacadista Alimentos Rio Grande", "Frigorífico Boa Carne", "Laticínios Serra Verde",
                    "Hortifruti Ceasa Express", "Bebidas Distribuidora Paulista"),
            "servicos", List.of("Papelaria Central Suprimentos", "TechParts Distribuidora", "Uniformes Brasil Ltda",
                    "Locadora de Veículos Rota"),
            "saude", List.of("Dental Med Distribuidora", "Cirúrgica São Lucas", "Hospitalar Brasil Suprimentos",
                    "Labor Diagnósticos Ltda"),
            "tecnologia", List.of("Nuvem Data Center Ltda", "Info Atacado Distribuidora", "Licenças & Software Ltda",
                    "DevTools Brasil"),
            "industria", List.of("Aços Vale do Paraíba", "Química Industrial Paulista", "Polímeros do Sul S.A.",
                    "Embalagens Tietê", "Transportadora Rodomax"),
            "agro", List.of("Cooperativa Agrícola Planalto", "Agroquímica Cerrado", "Sementes Boa Safra",
                    "Fertilizantes Centro-Oeste", "Posto Rodovia Diesel"),
            "construcao", List.of("Cimenteira Itapeva", "Depósito Areia & Pedra", "Aço Forte Vergalhões",
                    "Tintas Cor & Arte", "Madeireira Paraná"),
            "outro", List.of("Distribuidora Geral Brasil", "Comercial Atlântico", "Suprimentos Express",
                    "Logística Rápida Ltda"));

    static final List<String> SERVICOS = List.of("Companhia de Energia Elétrica", "Vivo Empresas - Internet",
            "Contabilidade Prisma Associados", "Limpeza Total Serviços", "Segurança Vigilant");

    static final List<String> IMOBILIARIAS = List.of("Imobiliária Lopes Prado", "Imobiliária Central Imóveis",
            "Administradora Horizonte Imóveis");

    static final List<String> BANCOS_CREDORES = List.of("Banco Horizonte", "Banco Cooperativo Sicredi",
            "Banco Atlântico S.A.");

    private static List<Product> p(Object... data) {
        Product[] out = new Product[data.length / 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Product((String) data[i * 3], ((Number) data[i * 3 + 1]).doubleValue(),
                    ((Number) data[i * 3 + 2]).doubleValue());
        }
        return List.of(out);
    }

    private static final Map<String, List<Product>> PRODUTOS = Map.of(
            "varejo", p("Camiseta básica algodão", 18, 35, "Calça jeans", 45, 90, "Tênis casual", 80, 160,
                    "Moletom canguru", 50, 90, "Bermuda sarja", 30, 55, "Vestido midi", 40, 90, "Jaqueta corta-vento", 90, 180,
                    "Kit meias 3 pares", 8, 15, "Boné aba curva", 15, 30, "Mochila urbana", 50, 110, "Cinto de couro", 25, 50,
                    "Óculos de sol", 30, 80, "Relógio digital", 40, 120, "Carteira de couro", 20, 45, "Pijama", 30, 60,
                    "Sandália", 25, 55, "Blusa tricô", 45, 85, "Camisa social", 40, 80, "Saia plissada", 30, 60,
                    "Bolsa transversal", 60, 150),
            "alimentacao", p("Farinha de trigo 25kg", 80, 110, "Queijo muçarela kg", 28, 40, "Presunto kg", 22, 32,
                    "Molho de tomate 2kg", 12, 20, "Carne moída kg", 30, 42, "Peito de frango kg", 14, 20, "Arroz 5kg", 22, 30,
                    "Feijão carioca 1kg", 7, 10, "Óleo de soja 900ml", 6, 9, "Refrigerante lata cx 12", 25, 35,
                    "Cerveja long neck cx 24", 70, 100, "Batata congelada 2kg", 18, 28, "Café torrado 1kg", 35, 50,
                    "Açúcar 5kg", 18, 25, "Embalagem delivery pct 100", 30, 50, "Guardanapo cx", 15, 25,
                    "Leite integral cx 12", 50, 65, "Ovos bandeja 30", 18, 25, "Bacon kg", 30, 42, "Água mineral cx 12", 12, 18),
            "servicos", p("Papel A4 cx 10 resmas", 180, 240, "Toner impressora", 150, 350, "Kit limpeza", 40, 80,
                    "Uniforme operacional", 50, 90, "Kit ferramentas", 80, 200, "Kit EPI", 60, 120, "Cabo de rede 305m", 350, 600,
                    "Material de escritório kit", 30, 60, "Café torrado 1kg", 35, 50, "Copo descartável cx", 60, 90,
                    "Pilha alcalina cx", 40, 70, "Fita isolante cx", 30, 55, "Lâmpada LED cx 10", 70, 120,
                    "Escada alumínio", 250, 450, "Extensão elétrica", 35, 70),
            "saude", p("Luva de procedimento cx 100", 25, 40, "Máscara cirúrgica cx 50", 12, 20, "Seringa 5ml cx 100", 35, 55,
                    "Gaze estéril pct", 8, 15, "Álcool 70% 1L", 8, 14, "Anestésico tubete cx", 90, 160, "Resina composta", 60, 120,
                    "Fio de sutura cx", 90, 180, "Avental descartável pct 10", 25, 45, "Lençol descartável rolo", 20, 35,
                    "Esparadrapo", 6, 12, "Soro fisiológico 500ml", 5, 9, "Termômetro digital", 20, 40,
                    "Agulha descartável cx 100", 20, 35, "Clorexidina 1L", 18, 30),
            "tecnologia", p("Notebook corporativo", 3500, 6000, "Monitor 24 polegadas", 700, 1100, "Mouse sem fio", 40, 90,
                    "Teclado mecânico", 150, 350, "Headset USB", 120, 300, "SSD 1TB", 350, 600, "Memória 16GB", 250, 400,
                    "Roteador Wi-Fi 6", 250, 700, "Switch 24 portas", 800, 1600, "Licença antivírus", 60, 120,
                    "Cabo HDMI 2m", 15, 35, "Webcam Full HD", 150, 350, "Nobreak 1500VA", 450, 900, "Dock USB-C", 250, 500,
                    "Servidor mini torre", 4000, 8000),
            "industria", p("Chapa de aço 2mm", 180, 320, "Parafuso sextavado cx 500", 60, 120, "Tinta epóxi 18L", 350, 600,
                    "Rolamento blindado", 25, 90, "Correia industrial", 40, 110, "Resina PP saco 25kg", 180, 260,
                    "Caixa de papelão", 2, 5, "Óleo hidráulico 20L", 250, 400, "Eletrodo de solda kg", 25, 45,
                    "Disco de corte", 6, 15, "Motor elétrico 5cv", 1800, 3200, "Válvula pneumática", 150, 400,
                    "Mangueira hidráulica m", 30, 70, "Filtro de ar industrial", 80, 200, "Palete de madeira", 35, 60),
            "agro", p("Semente de soja sc 40kg", 250, 400, "Semente de milho sc", 600, 1100, "Ureia sc 50kg", 150, 230,
                    "Fertilizante NPK sc 50kg", 170, 260, "Herbicida 20L", 380, 600, "Fungicida 5L", 300, 650,
                    "Inseticida 1L", 90, 200, "Ração bovina sc 40kg", 70, 110, "Sal mineral sc 30kg", 80, 140,
                    "Vacina bovina frasco", 40, 70, "Arame farpado rolo", 350, 500, "Calcário t", 120, 200,
                    "Lona plástica", 150, 300, "Filtro de trator", 80, 250, "Óleo lubrificante 20L", 300, 480),
            "construcao", p("Cimento CP II 50kg", 32, 42, "Areia média m³", 90, 140, "Brita 1 m³", 100, 150,
                    "Tijolo milheiro", 600, 900, "Vergalhão 10mm barra", 35, 55, "Argamassa 20kg", 15, 25,
                    "Tinta acrílica 18L", 220, 380, "Piso cerâmico m²", 25, 60, "Tubo PVC 100mm 6m", 50, 90,
                    "Fio elétrico 2,5mm 100m", 180, 280, "Telha fibrocimento", 35, 60, "Porta de madeira", 250, 450,
                    "Janela de alumínio", 400, 800, "Rejunte kg", 5, 10, "Caixa d'água 1000L", 350, 550),
            "outro", p("Produto de revenda linha A", 20, 60, "Produto de revenda linha B", 40, 120, "Embalagem padrão", 2, 6,
                    "Material de consumo", 10, 30, "Kit promocional", 25, 70, "Acessório", 15, 45, "Peça de reposição", 30, 150,
                    "Insumo básico", 8, 25, "Item sazonal", 30, 90, "Produto importado", 80, 250, "Refil", 10, 35,
                    "Display de vitrine", 120, 300, "Etiqueta adesiva rolo", 15, 30, "Sacola personalizada pct", 40, 80,
                    "Brinde corporativo", 12, 40));

    static String skuPrefix(String segmento) {
        return SKU_PREFIX.get(segmento);
    }

    static double ticket(String segmento) {
        return TICKET.get(segmento);
    }

    static double[] weekday(String segmento) {
        return WEEKDAY.get(segmento);
    }

    static OtherMix mix(String segmento) {
        return MIX.get(segmento);
    }

    static List<String> canais(String segmento) {
        return CANAIS.get(segmento);
    }

    static List<String> fornecedores(String segmento) {
        return FORNECEDORES.get(segmento);
    }

    static List<Product> produtos(String segmento) {
        return PRODUTOS.get(segmento);
    }
}
