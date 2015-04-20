(ns comportexviz.main
  (:require [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.protocols :as p]
            [comportexviz.controls-ui :as cui]
            [comportexviz.viz-canvas :as viz]
            [comportexviz.plots :as plots]
            [reagent.core :as reagent :refer [atom]]
            [cljs.core.async :as async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defn- tap-c
  [mult]
  (let [c (chan)]
    (async/tap mult c)
    c))

;;; ## Data

(def model (atom nil))
(def world (atom (chan)))

(def selection (atom {:region nil, :layer nil, :dt 0, :col nil}))

(def steps-c (chan))
(def steps-mult (async/mult steps-c))

(def main-options
  (atom {:sim-go? false
         :sim-step-ms 200
         :anim-go? true
         :anim-every 1}))

;;; ## Simulation

(defn sim-step!
  []
  (go
   (when-let [in-value (<! @world)]
     (->> (swap! model p/htm-step in-value)
          (put! steps-c)))))

(defn now [] (.getTime (js/Date.)))

(defn run-sim
  []
  (when @model
    (swap! model assoc
           :run-start {:time (now)
                       :timestep (p/timestep @model)}))
  (go
   (while (:sim-go? @main-options)
     (let [tc (async/timeout (:sim-step-ms @main-options))]
       (<! (sim-step!))
       (<! tc)))))

(add-watch main-options :run-sim
           (fn [_ _ old v]
             (when (and (:sim-go? v)
                        (not (:sim-go? old)))
               (run-sim))))

;;; ## Plots

(defn update-ts-plot
  [el agg-ts]
  (plots/bind-ts-plot el agg-ts 400 180
                      [:active :active-predicted :predicted]
                      viz/state-colors))

#_(defn init-plots!
  [init-model el]
  (bind! el
         [:div
          (for [[region-key rgn] (:regions init-model)
                layer-id (core/layers rgn)
                :let [uniqix (str (name region-key) (name layer-id))
                      el-id (str "comportex-plot-" uniqix)]]
            [:fieldset
             [:legend (str (name region-key) " " (name layer-id))]
             [:div {:id el-id}]])])
  (doseq [[region-key rgn] (:regions init-model)
          layer-id (core/layers rgn)
          :let [uniqix (str (name region-key) (name layer-id))
                el-id (str "comportex-plot-" uniqix)]]
    (let [this-el (->dom (str "#" el-id))
          freqs-c (async/map< (comp #(core/column-state-freqs % layer-id)
                                    region-key
                                    :regions)
                              (tap-c steps-mult))
          agg-freqs-ts (plots/aggregated-ts-ref freqs-c 200)]
      (add-watch agg-freqs-ts :ts-plot
                 (fn [_ _ _ v]
                   (update-ts-plot this-el v))))))

;;; ## Visualisation

(defn draw!
  []
  (.requestAnimationFrame js/window
                          #(viz/draw! @selection)))

(add-watch viz/viz-options :redraw
           (fn [_ _ _ _]
             (draw!)))

(add-watch selection :redraw
           (fn [_ _ _ _]
             (draw!)))

;;; # Animation loop

(go (loop [c (tap-c steps-mult)]
      (when-let [state (<! c)]
        (let [t (p/timestep state)
              n (:anim-every @main-options)]
          (when (and (:anim-go? @main-options)
                     (zero? (mod t n)))
            (draw!)))
        (recur c))))

(defn step-forward!
  []
  (viz/step-forward! selection sim-step!))

(defn step-backward!
  []
  (viz/step-backward! selection))

;;; ## Entry point

(defn comportexviz-app
  [model-tab]
  (cui/comportexviz-app model-tab model selection main-options viz/viz-options step-forward! step-backward!))

(defn- init-ui!
  [init-model]
  ;(init-plots! init-model (->dom "#comportex-plots"))
  (viz/init! init-model (tap-c steps-mult) selection sim-step!))

(defn- re-init-ui!
  [model]
  (viz/re-init! model))

(defn set-model
  [x]
  (let [init? (nil? @model)]
    (reset! model x)
    (if init?
      (init-ui! x)
      (re-init-ui! x))
    (let [region-key (first (core/region-keys x))
          layer-id (first (core/layers (get-in x [:regions region-key])))]
      (swap! selection assoc :region region-key :layer layer-id
             :dt 0 :col nil))))

(defn set-world
  [c]
  (when-let [old-c @world]
    (async/close! old-c))
  (reset! world c))
