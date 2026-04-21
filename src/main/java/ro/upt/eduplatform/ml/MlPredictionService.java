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

        double[][] X = new double[usable.size()][6];
        int[] y = new int[usable.size()];

        for (int i = 0; i < usable.size(); i++) {
            BacResult r = usable.get(i);
            X[i] = extractFeatures(r);
            y[i] = Boolean.TRUE.equals(r.getIsPassed()) ? 1 : 0;
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
        return lastMetrics;
    }

    /**
     * Predict BAC outcome for a given student profile.
     */
    public PredictionResult predict(PredictionRequest req) {
        if (!modelTrained) {
            log.info("Model not trained yet, training now...");
            trainModel();
        }

        double[] features = extractFeaturesFromRequest(req);
        double probability = modelTrained ? trainedModel.predictProbability(features) : 0.5;

        double estimatedAverage = estimateAverage(req);
        String category = gradeCategory(estimatedAverage);

        String riskLevel;
        if (probability >= 0.75)      riskLevel = "Low risk";
        else if (probability >= 0.5)  riskLevel = "Moderate risk";
        else                          riskLevel = "High risk";

        Map<String, Double> importance = computeFeatureImportance(features);

        return new PredictionResult(
                round3(probability),
                category,
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

    private double estimateAverage(PredictionRequest req) {
        List<Double> grades = new ArrayList<>();

        if (req.romanianGrade() != null)         grades.add(req.romanianGrade());
        if (req.mandatorySubjectGrade() != null)  grades.add(req.mandatorySubjectGrade());
        if (req.electiveSubjectGrade() != null)   grades.add(req.electiveSubjectGrade());

        if (req.enRomanianGrade() != null) grades.add(req.enRomanianGrade());
        if (req.enMathGrade() != null)     grades.add(req.enMathGrade());
        if (req.enAverage() != null)       grades.add(req.enAverage());

        if (grades.isEmpty()) return 5.0;

        double avg = grades.stream().mapToDouble(Double::doubleValue).average().orElse(5.0);
        return Math.min(10.0, Math.max(1.0, avg));
    }

    private Map<String, Double> computeFeatureImportance(double[] features) {
        Map<String, Double> importance = new LinkedHashMap<>();
        importance.put("Romanian Grade",           0.30);
        importance.put("Mandatory Subject Grade",  0.28);
        importance.put("Elective Subject Grade",   0.22);
        importance.put("Specialization Profile",   0.10);
        importance.put("County",                   0.06);
        importance.put("Environment (urban/rural)", 0.04);
        return importance;
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

    private static double orDefault(Double val, double def) {
        return val != null ? val : def;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static class RandomForestModel {
        private final List<DecisionTree> trees;
        private final Random rng;

        RandomForestModel(int nTrees, double[][] X, int[] y) {
            this.rng = new Random(42);
            this.trees = new ArrayList<>();
            int n = X.length;
            int nFeatures = X[0].length;
            int featuresPerTree = (int) Math.sqrt(nFeatures);

            for (int t = 0; t < nTrees; t++) {
                double[][] xBoot = new double[n][nFeatures];
                int[] yBoot = new int[n];
                for (int i = 0; i < n; i++) {
                    int idx = rng.nextInt(n);
                    xBoot[i] = X[idx].clone();
                    yBoot[i] = y[idx];
                }
                int[] featSubset = randomFeatureSubset(nFeatures, featuresPerTree);
                trees.add(new DecisionTree(xBoot, yBoot, featSubset, 8));
            }
            log.info("Random Forest trained: {} trees, {} features per tree", nTrees, featuresPerTree);
        }

        int predict(double[] x) {
            return predictProbability(x) >= 0.5 ? 1 : 0;
        }

        double predictProbability(double[] x) {
            double sum = 0;
            for (DecisionTree tree : trees) sum += tree.predict(x);
            return sum / trees.size();
        }

        private int[] randomFeatureSubset(int total, int k) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) all.add(i);
            Collections.shuffle(all, rng);
            int[] subset = new int[k];
            for (int i = 0; i < k; i++) subset[i] = all.get(i);
            return subset;
        }
    }

    private static class DecisionTree {
        private TreeNode root;

        DecisionTree(double[][] X, int[] y, int[] featureSubset, int maxDepth) {
            this.root = buildTree(X, y, featureSubset, 0, maxDepth);
        }

        double predict(double[] x) {
            return traverse(root, x);
        }

        private double traverse(TreeNode node, double[] x) {
            if (node.isLeaf) return node.value;
            if (x[node.featureIdx] <= node.threshold) return traverse(node.left, x);
            return traverse(node.right, x);
        }

        private TreeNode buildTree(double[][] X, int[] y, int[] features, int depth, int maxDepth) {
            int pos = 0;
            for (int yi : y) if (yi == 1) pos++;
            double leafVal = (double) pos / y.length;

            if (depth >= maxDepth || y.length <= 5 || pos == 0 || pos == y.length) {
                TreeNode leaf = new TreeNode();
                leaf.isLeaf = true;
                leaf.value = leafVal;
                return leaf;
            }

            int bestFeat = features[0];
            double bestThresh = 0, bestGini = Double.MAX_VALUE;

            for (int f : features) {
                double[] vals = new double[X.length];
                for (int i = 0; i < X.length; i++) vals[i] = X[i][f];
                Arrays.sort(vals);

                for (int i = 0; i < vals.length - 1; i++) {
                    if (vals[i] == vals[i + 1]) continue;
                    double thresh = (vals[i] + vals[i + 1]) / 2.0;
                    double gini = giniSplit(X, y, f, thresh);
                    if (gini < bestGini) {
                        bestGini = gini; bestFeat = f; bestThresh = thresh;
                    }
                }
            }

            List<Integer> leftIdx = new ArrayList<>(), rightIdx = new ArrayList<>();
            for (int i = 0; i < X.length; i++) {
                if (X[i][bestFeat] <= bestThresh) leftIdx.add(i); else rightIdx.add(i);
            }

            if (leftIdx.isEmpty() || rightIdx.isEmpty()) {
                TreeNode leaf = new TreeNode();
                leaf.isLeaf = true; leaf.value = leafVal;
                return leaf;
            }

            double[][] xLeft  = subset(X, leftIdx),  xRight  = subset(X, rightIdx);
            int[] yLeft = subsetY(y, leftIdx), yRight = subsetY(y, rightIdx);

            TreeNode node = new TreeNode();
            node.featureIdx = bestFeat;
            node.threshold  = bestThresh;
            node.left  = buildTree(xLeft,  yLeft,  features, depth + 1, maxDepth);
            node.right = buildTree(xRight, yRight, features, depth + 1, maxDepth);
            return node;
        }

        private double giniSplit(double[][] X, int[] y, int feat, double thresh) {
            int lp = 0, lt = 0, rp = 0, rt = 0;
            for (int i = 0; i < X.length; i++) {
                if (X[i][feat] <= thresh) { lt++; if (y[i] == 1) lp++; }
                else { rt++; if (y[i] == 1) rp++; }
            }
            double gL = lt > 0 ? 1 - sq((double) lp / lt) - sq(1.0 - (double) lp / lt) : 0;
            double gR = rt > 0 ? 1 - sq((double) rp / rt) - sq(1.0 - (double) rp / rt) : 0;
            return (lt * gL + rt * gR) / X.length;
        }

        private double sq(double v) { return v * v; }

        private double[][] subset(double[][] X, List<Integer> idx) {
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