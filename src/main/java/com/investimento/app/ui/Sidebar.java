package com.investimento.app.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sidebar fixa (244px, escura) com os 7 itens de navegacao, em 2 grupos
 * ("CARTEIRA" / "GESTÃO"). Receita: {@code
 * .claude/skills/design-system/references/componentes.md} -> "Sidebar de
 * navegação".
 *
 * <p>O card de rodape "FONTES DE DADOS" descrito em componentes.md
 * (status ao vivo de HG Brasil/brapi.dev/CoinGecko) nao foi incluido aqui de
 * proposito: a propria ATV-07 nao o lista no passo a passo de Sidebar.java, e
 * ele dependeria de dados reais de monitoramento que ainda nao existem nesta
 * fase (essa atividade so implementa a casca + navegacao).</p>
 */
public class Sidebar extends VBox {

    private final Consumer<Screen> onSelect;
    private final Map<Screen, HBox> items = new EnumMap<>(Screen.class);

    public Sidebar(Consumer<Screen> onSelect) {
        this.onSelect = onSelect;
        getStyleClass().add("sidebar");

        getChildren().add(buildLogo());
        getChildren().add(buildGroup("CARTEIRA", "PORTFOLIO"));
        getChildren().add(buildGroup("GESTÃO", "MANAGEMENT"));
    }

    /** Reestiliza os itens: só o item de {@code screen} fica ativo. */
    public void markActive(Screen screen) {
        for (Map.Entry<Screen, HBox> entry : items.entrySet()) {
            boolean active = entry.getKey() == screen;
            HBox item = entry.getValue();
            item.getStyleClass().removeAll("sidebar-nav-item", "sidebar-nav-item-active");
            item.getStyleClass().add(active ? "sidebar-nav-item-active" : "sidebar-nav-item");
        }
    }

    private HBox buildLogo() {
        Region square = new Region();
        square.getStyleClass().add("sidebar-logo-square");
        square.setMinSize(30, 30);
        square.setMaxSize(30, 30);
        square.setStyle("-fx-background-color: -fx-color-accent; -fx-background-radius: 9;");

        Label letter = new Label("C");
        letter.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 15px; -fx-text-fill: -fx-color-on-accent;");

        StackPane logoIcon = new StackPane(square, letter);
        logoIcon.setMinSize(30, 30);
        logoIcon.setMaxSize(30, 30);

        Label appName = new Label("Carteira");
        appName.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 16px; -fx-text-fill: -fx-color-sidebar-text-active;");

        HBox logo = new HBox(10, logoIcon, appName);
        logo.setAlignment(Pos.CENTER_LEFT);
        logo.setPadding(new Insets(0, 8, 0, 8));
        return logo;
    }

    private VBox buildGroup(String eyebrowText, String groupKey) {
        Label eyebrow = new Label(eyebrowText);
        eyebrow.getStyleClass().add("sidebar-eyebrow");
        eyebrow.setPadding(new Insets(0, 8, 8, 8));

        VBox group = new VBox(3, eyebrow);
        for (Screen screen : Screen.values()) {
            if (screen.group.equals(groupKey)) {
                group.getChildren().add(buildNavItem(screen));
            }
        }
        return group;
    }

    private HBox buildNavItem(Screen screen) {
        Region dot = new Region();
        dot.getStyleClass().add("dot");

        Label label = new Label(screen.label);

        HBox item = new HBox(10, dot, label);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("sidebar-nav-item");
        // Garante area clicavel em toda a extensao do item, mesmo sem
        // background pintado no estado inativo (ver armadilhas da ATV-07).
        item.setPickOnBounds(true);
        item.setOnMouseClicked(e -> onSelect.accept(screen));

        items.put(screen, item);
        return item;
    }
}
