# MEM-003 — Contratos

## MEM-003-DOM-01 — `TemplateDefinition`

O catálogo é dado declarativo local, sem endpoint HTTP nesta feature.

```ts
type TemplateOrientation = "portrait" | "square" | "landscape";
type PhotoSlotDefinition = {
  slotId: string;
  x: number;
  y: number;
  width: number;
  height: number;
  layerOrder: number;
};
type TemplateDecorationDefinition = {
  decorationId: "polaroid-paper";
  styleVersion: 1;
  x: number;
  y: number;
  width: number;
  height: number;
  layerOrder: number;
};
type TemplateDefinition = {
  templateId: string;
  version: number;
  name: string;
  description: string;
  photoCount: number;
  aspectRatio: number; // canvasWidth / canvasHeight
  slots: PhotoSlotDefinition[];
  decorations: TemplateDecorationDefinition[];
  export: {
    width: number;
    height: number;
    formats: Array<"image/png" | "image/jpeg">;
    jpegQuality: number; // inclusive range [0, 1]
  };
};
```

`x`, `y`, `width`, `height` ocupam o espaço normalizado `[0,1]` do canvas. `layerOrder` é inteiro único entre slots e decorações. O catálogo informa orientação derivada de `aspectRatio`; não duplica a orientação como dado mutável.

`aspectRatio` precisa corresponder a `export.width / export.height` com tolerância `1e-9`. A orientação é derivada: `<1` retrato, `=1` quadrado, `>1` paisagem. Mudanças em slots, decoração ou exportação que alterem o resultado visual são incompatíveis e criam nova versão. `TemplateDefinition.version` será referenciado por futuras composições como `templateVersion`; não é `Composition.schemaVersion` nem a versão do documento.

## MEM-003-DOM-02 — Catálogo v1

| `templateId` | Nome | Fotos | Proporção | Orientação | Canvas de exportação |
|---|---|---:|---:|---|---:|
| `single-photo` | Single | 1 | 1:1 | square | 1600×1600 |
| `duo-side-by-side` | Duo | 2 | 4:3 | landscape | 1600×1200 |
| `trio-featured` | Trio | 3 | 4:3 | landscape | 1600×1200 |
| `classic-strip` | Classic strip | 4 | 2:3 | portrait | 1200×1800 |
| `grid-four` | Grid | 4 | 1:1 | square | 1600×1600 |
| `polaroid-single` | Polaroid | 1 | 4:5 | portrait | 1200×1500 |
| `grid-six` | Grid 6 | 6 | 4:3 | landscape | 1800×1350 |
| `story-trio` | Story trio | 3 | 2:3 | portrait | 1200×1800 |

Todos usam `version: 1` e formatos `image/png`, `image/jpeg`, `jpegQuality: 0.92`. A validação aceita apenas valores JPEG no intervalo `[0,1]`; dimensões de exportação são inteiros positivos.

### Geometria normalizada por slot

As coordenadas abaixo são `{slotId: [x, y, width, height, layerOrder]}`.

- `single-photo`: `photo-1 [0, 0, 1, 1, 0]`.
- `duo-side-by-side`: `photo-1 [0, 0, 0.5, 1, 0]`, `photo-2 [0.5, 0, 0.5, 1, 1]`.
- `trio-featured`: `photo-1 [0, 0, 0.62, 1, 0]`, `photo-2 [0.62, 0, 0.38, 0.5, 1]`, `photo-3 [0.62, 0.5, 0.38, 0.5, 2]`.
- `classic-strip`: quatro slots de largura 1 e altura 0.25; `photo-i [0, (i-1)×0.25, 1, 0.25, i-1]`, para `i=1..4`.
- `grid-four`: `photo-1 [0, 0, 0.5, 0.5, 0]`, `photo-2 [0.5, 0, 0.5, 0.5, 1]`, `photo-3 [0, 0.5, 0.5, 0.5, 2]`, `photo-4 [0.5, 0.5, 0.5, 0.5, 3]`.
- `polaroid-single`: decoração `polaroid-paper` v1 cobre `[0, 0, 1, 1]` e layer order 0 como fundo de papel linho; `photo-1 [0.08, 0.06, 0.84, 0.72, 1]` deixa margem lateral/superior e faixa inferior vazia. O catálogo não inclui texto na faixa.
- `grid-six`: `photo-i [(i-1 mod 3)/3, floor((i-1)/3)×0.5, 1/3, 0.5, i-1]`, para `i=1..6`, em ordem de leitura.
- `story-trio`: `photo-1 [0, 0, 1, 0.62, 0]`, `photo-2 [0, 0.62, 0.5, 0.38, 1]`, `photo-3 [0.5, 0.62, 0.5, 0.38, 2]`.

## MEM-003-DOM-03 — Filtros

```ts
type TemplateCatalogFilter = {
  photoCount?: 1 | 2 | 3 | 4 | 6;
  aspectRatio?: "1:1" | "4:3" | "2:3" | "4:5";
};
```

Filtros de quantidade e proporção são combinados com `AND`. Valores ausentes significam “Todos”. A proporção selecionada é exata; orientação não é filtro. A ordem retornada é a ordem estável de `MEM-003-DOM-02`.

## MEM-003-UI-01 — Seleção local

- Um cartão usa botão com nome acessível contendo nome e quantidade.
- A seleção usa `aria-pressed`; a interface também mostra texto visível de selecionado.
- Uma região `role="status"` com anúncio educado descreve nome e quantidade quando a seleção muda.
- Selecionar outro cartão desmarca o anterior. Selecionar o mesmo cartão mantém a seleção.
- Se um filtro ocultar o cartão selecionado, a seleção é limpa e a região de status anuncia que ela foi removida.
- O catálogo não faz navegação, gravação ou requisição ao selecionar. O estado vive somente enquanto a tela está montada.
