package dev.visualengine.model;

/** Curated configurations composed from the shared analytic and persistent systems. */
public final class Recipes {
    private Recipes() {}
    public static void apply(Settings s,Settings.Recipe recipe) {
        s.recipe=recipe;s.name=recipe.name().toLowerCase().replace('_',' ');
        s.feedbackEnabled=s.pigmentEnabled=s.reactionEnabled=s.particlesEnabled=false;
        s.set("role",0);s.set("historyMix",.35);
        switch(recipe) {
            case COSMIC_SILK -> {s.particlesEnabled=true;s.feedbackEnabled=true;s.palette=3;}
            case ALIEN_ECOSYSTEM,INTERFERENCE_GROWTH,INTERNAL_WEATHER -> {s.reactionEnabled=true;s.palette=1;}
            case TEMPORAL_PAINTING,TIME_EXCAVATION -> s.feedbackEnabled=true;
            case FLUID_PALETTE -> {s.pigmentEnabled=true;s.palette=2;}
            case ROLE_EXCHANGE -> s.set("role",.5);
            case LIVING_CONTOURS -> {s.set("scale",1.2);s.palette=1;}
            default -> {}
        }
    }
}
