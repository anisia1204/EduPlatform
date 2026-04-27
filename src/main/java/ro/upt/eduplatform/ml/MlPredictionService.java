package ro.upt.eduplatform.ml;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ro.upt.eduplatform.model.BacResult;
import ro.upt.eduplatform.repository.BacResultRepository;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlPredictionService {

    private final BacResultRepository bacRepository;

    private static final int N_FEATURES = 6;
    private static final String[] FEATURE_NAMES = {
            "Romanian Grade",
            "Mandatory Subject Grade",
            "Elective Subject Grade",
            "Specialization Profile",
            "County",
            "Environment (urban/rural)"
    };

    private RandomForestModel trainedModel = null;
    private boolean modelTrained = false;
    private ModelMetrics lastMetrics = null;

    private static final List<String> COUNTIES = List.of(
            "AB","AR","AG","BC","BH","BN","BT","BV","BR","B","BZ","CS",
            "CL","CJ","CT","CV","DB","DJ","GL","GR","GJ","HR","HD","IL",
            "IS","IF","MM","MH","MS","NT","OT","PH","SM","SJ","SB","SV",
            "TR","TM","TL","VS","VL","VN"
    );

    public record PredictionRequest(
            String county,
            String profile,
            String environment,
            Double romanianGrade,
            Double mandatorySubjectGrade,
            Double electiveSubjectGrade,
            Double enRomanianGrade,
            Double enMathGrade,
            Double enAverage
    ) {}

    public record PredictionResult(
            Double passingProbability,
            String predictedCategory,
            Double estimatedAverage,
            String riskLevel,
            Map<String, Double> featureImportance
    ) {}

    public record ModelMetrics(
            Double accuracy,
            Double precision,
            Double recall,
            Double f1Score,
            Integer trainingExamples,
            String status
    ) {}

    public ModelMetrics trainModel() {
        log.info("Starting ML model training...");

        List<BacResult> data = bacRepository.findAllWithCompleteData();
        List<BacResult> usable = data.stream()
                .filter(r -> r.getRomanianGrade() != null
                        && r.getMandatorySubjectGrade() != null
                        && r.getIsPassed() != null
                        && r.getCounty() != null
                        && !"XX".equals(r.getCounty()))
                .collect(Collectors.toList());

        log.info("Total BAC records: {}, usable for training: {}", data.size(), usable.size());

        if (usable.size() < 100) {
            return new ModelMetrics(0.0, 0.0, 0.0, 0.0, usable.size(), "INSUFFICIENT_DATA");
        }

        Collections.shuffle(usable, new Random(42));

        double[][] X = new double[usable.size()][N_FEATURES];
        int[] y = new int[usable.size()];
        for (int i = 0; i < usable.size(); i++) {
            X[i] = extractFeatures(usable.get(i));
            y[i] = Boolean.TRUE.equals(usable.get(i).getIsPassed()) ? 1 : 0;
        }

        int splitIdx = (int) (usable.size() * 0.8);
        double[][] xTrain = Arrays.copyOfRange(X, 0, splitIdx);
        double[][] xTest  = Arrays.copyOfRange(X, splitIdx, X.length);
        int[] yTrain = Arrays.copyOfRange(y, 0, splitIdx);
        int[] yTest  = Arrays.copyOfRange(y, splitIdx, y.length);

        trainedModel = new RandomForestModel(100, xTrain, yTrain);
        modelTrained = true;

        int correct = 0, tp = 0, fp = 0, fn = 0;
        for (int i = 0; i < xTest.length; i++) {
            int pred = trainedModel.predict(xTest[i]);
            if (pred == yTest[i]) correct++;
            if (pred == 1 && yTest[i] == 1) tp++;
            if (pred == 1 && yTest[i] == 0) fp++;
            if (pred == 0 && yTest[i] == 1) fn++;
        }

        double accuracy  = (double) correct / xTest.length;
        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall    = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0;

        lastMetrics = new ModelMetrics(
                round3(accuracy), round3(precision), round3(recall), round3(f1),
                usable.size(), "TRAINED"
        );

        log.info("Model trained: accuracy={}, f1={}, n={}",
                lastMetrics.accuracy(), lastMetrics.f1Score(), usable.size());

        double[] imp = trainedModel.getFeatureImportance();
        for (int i = 0; i < FEATURE_NAMES.length; i++) {
            log.info("  Feature importance [{}]: {}", FEATURE_NAMES[i], round3(imp[i]));
        }

        return lastMetrics;
    }


    public PredictionResult predict(PredictionRequest req) {
        if (!modelTrained) {
            log.info("Model not trained yet, training now...");
            trainModel();
        }

        double[] features = extractFeaturesFromRequest(req);
        double probability = trainedModel.predictProbability(features);

        double estimatedAverage = estimateAverage(req);

        String riskLevel;
        if (probability >= 0.75)      riskLevel = "Low risk";
        else if (probability >= 0.5)  riskLevel = "Moderate risk";
        else                          riskLevel = "High risk";

        Map<String, Double> importance = buildImportanceMap(trainedModel.getFeatureImportance());

        return new PredictionResult(
                round3(probability),
                gradeCategory(estimatedAverage),
                round2(estimatedAverage),
                riskLevel,
                importance
        );
    }

    private double[] extractFeatures(BacResult r) {
        return new double[]{
                orDefault(r.getRomanianGrade(), 5.0),
                orDefault(r.getMandatorySubjectGrade(), 5.0),
                orDefault(r.getElectiveSubjectGrade(), 5.0),
                encodeProfile(r.getProfile()),
                encodeCounty(r.getCounty()),
                encodeEnvironment(r.getEnvironment())
        };
    }

    private double[] extractFeaturesFromRequest(PredictionRequest req) {
        return new double[]{
                orDefault(req.romanianGrade(), 5.0),
                orDefault(req.mandatorySubjectGrade(), 5.0),
                orDefault(req.electiveSubjectGrade(), 5.0),
                encodeProfile(req.profile()),
                encodeCounty(req.county()),
                encodeEnvironment(req.environment())
        };
    }

    private double estimateAverage(PredictionRequest req) {
        List<Double> grades = new ArrayList<>();
        if (req.romanianGrade() != null)         grades.add(req.romanianGrade());
        if (req.mandatorySubjectGrade() != null)  grades.add(req.mandatorySubjectGrade());
        if (req.electiveSubjectGrade() != null)   grades.add(req.electiveSubjectGrade());
        if (req.enRomanianGrade() != null)        grades.add(req.enRomanianGrade());
        if (req.enMathGrade() != null)            grades.add(req.enMathGrade());
        if (req.enAverage() != null)              grades.add(req.enAverage());
        if (grades.isEmpty()) return 5.0;
        return Math.min(10.0, Math.max(1.0,
                grades.stream().mapToDouble(Double::doubleValue).average().orElse(5.0)));
    }

    private Map<String, Double> buildImportanceMap(double[] importance) {
        Map<String, Double> map = new LinkedHashMap<>();
        Integer[] idx = new Integer[FEATURE_NAMES.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(importance[b], importance[a]));
        for (int i : idx) {
            map.put(FEATURE_NAMES[i], round3(importance[i]));
        }
        return map;
    }

    private double encodeProfile(String profile) {
        if (profile == null) return 2.0;
        String p = profile.toLowerCase();
        if (p.contains("math") || p.contains("info") || p.contains("mate")) return 0.0;
        if (p.contains("science") || p.contains("natur") || p.contains("stiint")) return 1.0;
        if (p.contains("philol") || p.contains("filol") || p.contains("human")) return 2.0;
        if (p.contains("social")) return 3.0;
        return 4.0;
    }

    private double encodeCounty(String county) {
        if (county == null) return 21.0;
        int idx = COUNTIES.indexOf(county.toUpperCase());
        return idx >= 0 ? idx : 21.0;
    }

    private double encodeEnvironment(String env) {
        if (env == null) return 0.5;
        return env.toUpperCase().contains("URBAN") ? 1.0 : 0.0;
    }

    private String gradeCategory(double avg) {
        if (avg < 5) return "below 5";
        if (avg < 6) return "5-6";
        if (avg < 7) return "6-7";
        if (avg < 8) return "7-8";
        if (avg < 9) return "8-9";
        return "9-10";
    }

    public ModelMetrics getLastMetrics() { return lastMetrics; }
    public boolean isModelTrained()      { return modelTrained; }

    private static double orDefault(Double v, double d) { return v != null ? v : d; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static class RandomForestModel {
        private final List<DecisionTree> trees;
        private final double[] featureImportance;
        private final int nTotal;

        RandomForestModel(int nTrees, double[][] X, int[] y) {
            Random rng = new Random(42);
            this.trees = new ArrayList<>();
            this.nTotal = X.length;
            int nFeatures = X[0].length;
            int featuresPerSplit = (int) Math.sqrt(nFeatures);

            double[] rawImportance = new double[nFeatures];

            for (int t = 0; t < nTrees; t++) {
                double[][] xBoot = new double[nTotal][nFeatures];
                int[] yBoot = new int[nTotal];
                for (int i = 0; i < nTotal; i++) {
                    int idx = rng.nextInt(nTotal);
                    xBoot[i] = X[idx].clone();
                    yBoot[i] = y[idx];
                }
                int[] featSubset = randomSubset(nFeatures, featuresPerSplit, rng);
                DecisionTree tree = new DecisionTree(xBoot, yBoot, featSubset, 8, nTotal);
                trees.add(tree);

                double[] treeImportance = tree.getFeatureImportance();
                for (int f = 0; f < nFeatures; f++) {
                    rawImportance[f] += treeImportance[f];
                }
            }

            for (int f = 0; f < nFeatures; f++) rawImportance[f] /= nTrees;

            double sum = Arrays.stream(rawImportance).sum();
            this.featureImportance = new double[nFeatures];
            if (sum > 0) {
                for (int f = 0; f < nFeatures; f++) featureImportance[f] = rawImportance[f] / sum;
            }
        }

        int predict(double[] x) { return predictProbability(x) >= 0.5 ? 1 : 0; }

        double predictProbability(double[] x) {
            double sum = 0;
            for (DecisionTree t : trees) sum += t.predict(x);
            return sum / trees.size();
        }

        double[] getFeatureImportance() { return featureImportance.clone(); }

        private int[] randomSubset(int total, int k, Random rng) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) all.add(i);
            Collections.shuffle(all, rng);
            int[] sub = new int[k];
            for (int i = 0; i < k; i++) sub[i] = all.get(i);
            return sub;
        }
    }

    private static class DecisionTree {
        private final TreeNode root;
        private final double[] featureImportance;
        private final int nTotalSamples;

        DecisionTree(double[][] X, int[] y, int[] featureSubset, int maxDepth, int nTotal) {
            this.nTotalSamples = nTotal;
            this.featureImportance = new double[X[0].length];
            this.root = buildTree(X, y, featureSubset, 0, maxDepth);
        }

        double predict(double[] x) { return traverse(root, x); }

        double[] getFeatureImportance() { return featureImportance.clone(); }

        private double traverse(TreeNode node, double[] x) {
            if (node.isLeaf) return node.value;
            return x[node.featureIdx] <= node.threshold
                    ? traverse(node.left, x)
                    : traverse(node.right, x);
        }

        private TreeNode buildTree(double[][] X, int[] y, int[] features, int depth, int maxDepth) {
            int pos = 0;
            for (int yi : y) if (yi == 1) pos++;
            double leafVal = (double) pos / y.length;

            if (depth >= maxDepth || y.length <= 5 || pos == 0 || pos == y.length) {
                return leafNode(leafVal);
            }

            int bestFeat = -1;
            double bestThresh = 0, bestGini = Double.MAX_VALUE;
            double parentGini = gini(y);

            for (int f : features) {
                double[] vals = new double[X.length];
                for (int i = 0; i < X.length; i++) vals[i] = X[i][f];
                Arrays.sort(vals);
                for (int i = 0; i < vals.length - 1; i++) {
                    if (vals[i] == vals[i + 1]) continue;
                    double thresh = (vals[i] + vals[i + 1]) / 2.0;
                    double g = weightedGini(X, y, f, thresh);
                    if (g < bestGini) { bestGini = g; bestFeat = f; bestThresh = thresh; }
                }
            }

            if (bestFeat == -1) return leafNode(leafVal);

            double decrease = parentGini - bestGini;
            if (decrease > 0) {
                featureImportance[bestFeat] += ((double) X.length / nTotalSamples) * decrease;
            }

            List<Integer> leftIdx = new ArrayList<>(), rightIdx = new ArrayList<>();
            for (int i = 0; i < X.length; i++) {
                if (X[i][bestFeat] <= bestThresh) leftIdx.add(i); else rightIdx.add(i);
            }
            if (leftIdx.isEmpty() || rightIdx.isEmpty()) return leafNode(leafVal);

            TreeNode node = new TreeNode();
            node.featureIdx = bestFeat;
            node.threshold  = bestThresh;
            node.left  = buildTree(subsetX(X, leftIdx),  subsetY(y, leftIdx),  features, depth + 1, maxDepth);
            node.right = buildTree(subsetX(X, rightIdx), subsetY(y, rightIdx), features, depth + 1, maxDepth);
            return node;
        }

        private double gini(int[] y) {
            if (y.length == 0) return 0;
            int pos = 0;
            for (int yi : y) if (yi == 1) pos++;
            double p = (double) pos / y.length;
            return 1.0 - p * p - (1 - p) * (1 - p);
        }

        private double weightedGini(double[][] X, int[] y, int feat, double thresh) {
            int lp = 0, lt = 0, rp = 0, rt = 0;
            for (int i = 0; i < X.length; i++) {
                if (X[i][feat] <= thresh) { lt++; if (y[i] == 1) lp++; }
                else { rt++; if (y[i] == 1) rp++; }
            }
            double gL = lt > 0 ? 1 - sq((double) lp / lt) - sq(1 - (double) lp / lt) : 0;
            double gR = rt > 0 ? 1 - sq((double) rp / rt) - sq(1 - (double) rp / rt) : 0;
            return ((double) lt * gL + (double) rt * gR) / X.length;
        }

        private TreeNode leafNode(double val) {
            TreeNode n = new TreeNode(); n.isLeaf = true; n.value = val; return n;
        }
        private double sq(double v) { return v * v; }

        private double[][] subsetX(double[][] X, List<Integer> idx) {
            double[][] out = new double[idx.size()][];
            for (int i = 0; i < idx.size(); i++) out[i] = X[idx.get(i)].clone();
            return out;
        }

        private int[] subsetY(int[] y, List<Integer> idx) {
            int[] out = new int[idx.size()];
            for (int i = 0; i < idx.size(); i++) out[i] = y[idx.get(i)];
            return out;
        }
    }

    private static class TreeNode {
        boolean isLeaf = false;
        double value = 0;
        int featureIdx = 0;
        double threshold = 0;
        TreeNode left, right;
    }
}