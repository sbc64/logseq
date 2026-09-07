(ns logseq.common.export.file-test
  (:require [cljs.test :refer [deftest is testing]]
            [logseq.common.export.file :as export-file]))

(deftest sequential-property-values-export-without-recursing-on-the-container
  (let [render #(@#'export-file/property-value->string nil {} % {})]
    (testing "vectors and lists retain their order"
      (is (= "beta, alpha" (render ["beta" "alpha"])))
      (is (= "beta, alpha" (render '("beta" "alpha")))))
    (testing "nested sequences and empty values terminate"
      (is (= "alpha, beta, 3" (render ["alpha" ["beta" 3]])))
      (is (= "" (render [])))
      (is (= "" (render '()))))
    (testing "sets remain sorted and scalars remain scalar"
      (is (= "alpha, beta" (render #{"beta" "alpha"})))
      (is (= "alpha" (render "alpha"))))))
