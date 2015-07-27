(ns comportexviz.demos.q-learning-2d
  (:require [org.nfrac.comportex.demos.q-learning-2d :as demo]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.util :as util :refer [round abs]]
            [comportexviz.demos.q-learning-1d :refer [q-learning-sub-pane]]
            [comportexviz.main :as main]
            [comportexviz.helpers :as helpers :refer [resizing-canvas]]
            [comportexviz.plots-canvas :as plt]
            [comportexviz.server.browser :as server]
            [comportexviz.util :as utilv]
            [monet.canvas :as c]
            [reagent.core :as reagent :refer [atom]]
            [reagent-forms.core :refer [bind-fields]]
            [goog.dom :as dom]
            [goog.string :as gstr]
            [goog.string.format]
            [cljs.core.async :as async :refer [put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [comportexviz.macros :refer [with-ui-loading-message]]))

(def config
  (atom {:n-regions 1}))

(def world-c
  (async/chan (async/buffer 1)))

(def into-sim
  (atom nil))

(def model
  (atom nil))

(def raw-models-c
  (async/chan))

(defn feed-world!
  "Feed the world input channel continuously, selecting actions from
   state of model itself."
  []
  (demo/feed-world-c-with-actions! raw-models-c world-c model))

(defn draw-world
  [ctx in-value htm]
  (let [surface demo/surface
        x-max (count surface)
        y-max (count (first surface))
        x-lim [0 x-max]
        y-lim [0 y-max]
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        edge-px (min width-px height-px)
        plot-size {:w edge-px
                   :h edge-px}
        plot (plt/xy-plot ctx plot-size x-lim y-lim)]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (plt/frame! plot)
    (doseq [y (range (count surface))
            x (range (count (first surface)))
            :let [v (get-in surface [x y])]]
      (cond
        (>= v 10)
        (do (c/fill-style ctx "#66ff66")
            (plt/rect! plot x y 1 1))
        (<= v -10)
        (do (c/fill-style ctx "black")
            (plt/rect! plot x y 1 1))
        ))
    (doseq [[state-action q] (:Q-map in-value)
            :let [{:keys [x y dx dy]} state-action]]
      (c/fill-style ctx (if (pos? q) "green" "red"))
      (c/alpha ctx (abs q))
      (cond
        ;; from left
        (pos? dx)
        (plt/rect! plot (- x 0.25) y 0.25 1)
        ;; from right
        (neg? dx)
        (plt/rect! plot (+ x 1) y 0.25 1)
        ;; from above
        (pos? dy)
        (plt/rect! plot x (- y 0.25) 1 0.25)
        ;; from below
        (neg? dy)
        (plt/rect! plot x (+ y 1) 1 0.25)
        ))
    (c/alpha ctx 1)
    (c/stroke-style ctx "yellow")
    (c/fill-style ctx "#6666ff")
    (plt/point! plot (+ 0.5 (:x in-value)) (+ 0.5 (:y in-value)) 4)
    (c/stroke-style ctx "black")
    (plt/grid! plot {})))

(defn signed-str [x] (str (if (neg? x) "" "+") x))

(defn world-pane
  []
  (let [selected-htm (atom nil)]
    (add-watch main/selection ::fetch-selected-htm
               (fn [_ _ _ sel]
                 (when-let [model-id (:model-id sel)]
                   (let [out-c (async/chan)]
                     (put! @main/into-journal [:get-model model-id out-c])
                     (go
                       (reset! selected-htm (<! out-c)))))))
    (fn []
      (when-let [step (main/selected-step)]
        (when-let [htm @selected-htm]
          (let [in-value (first (:input-values step))
                DELTA (gstr/unescapeEntities "&Delta;")
                TIMES (gstr/unescapeEntities "&times;")]
            [:div
             [:p.muted [:small "Input on selected timestep."]]
             [:table.table.table-condensed
              [:tr
               [:th "x,y"]
               [:td [:small "position"]]
               [:td (:x in-value) "," (:y in-value)]]
              [:tr
               [:th (str DELTA "x," DELTA "y")]
               [:td [:small "action"]]
               [:td (str (signed-str (:dx in-value))
                         ","
                         (signed-str (:dy in-value)))]]
              [:tr
               [:th [:var "z"]]
               [:td [:small "~reward"]]
               [:td (signed-str (:z in-value))]]
              [:tr
               [:td {:colSpan 3}
                [:small "z " TIMES " 0.01 = " [:var "R"]]]]]
             (q-learning-sub-pane htm)
             ;; plot
             [resizing-canvas {:style {:width "100%"
                                       :height "240px"}}
              [main/selection selected-htm]
              (fn [ctx]
                (let [step (main/selected-step)
                      in-value (first (:input-values step))]
                  (draw-world ctx in-value @selected-htm)))
              nil]
             [:small
              [:p
               "Current position on the objective function surface. "
               "Also shows approx Q values for each position/action combination,
            where green is positive and red is negative.
            These are the last seen Q values including last adjustments."]
              ]]))))))

(defn set-model!
  []
  (utilv/close-and-reset! into-sim (async/chan))
  (utilv/close-and-reset! main/into-journal (async/chan))

  (with-ui-loading-message
    (reset! model (demo/make-model))
    (server/init model
                 world-c
                 @main/into-journal
                 @into-sim
                 raw-models-c)))

(def config-template
  [:div.form-horizontal
   [:div.form-group
    [:label.col-sm-5 "Number of regions:"]
    [:div.col-sm-7
     [:input.form-control {:field :numeric
                           :id :n-regions}]]]
   [:div.form-group
    [:div.col-sm-offset-5.col-sm-7
     [:button.btn.btn-default
      {:on-click (fn [e]
                   (set-model!)
                   (.preventDefault e))}
      "Restart with new model"]
     [:p.text-danger "This resets all parameters."]]]
   ])

(defn model-tab
  []
  [:div
   [:p "Highly experimental attempt at integrating "
    [:a {:href "http://en.wikipedia.org/wiki/Q-learning"} "Q learning"]
    " (reinforcement learning)."]
   [:h4 "General approach"]
   [:p "A Q value indicates the goodness of taking an action from some
        state. We represent a Q value by the average permanence of
        synapses activating the action from that state, minus the
        initial permanence value."]
   [:p "The action region columns are activated just like any other
        region, but are then interpreted to produce an action."]
   [:p "Adjustments to a Q value, based on reward and expected future
        reward, are applied to the permanence of synapses which
        directly activated the action (columns). This adjustment
        applies in the action layer only, where it replaces the usual
        learning of proximal synapses (spatial pooling)."]
   [:p "Exploration arises from the usual boosting of neglected
        columns, primarily in the action layer."]

   [:h4 "This example"]
   [:p "The agent can move up, down, left or right on a surface.
        The reward is -3 on normal squares, -200 on hazard squares
        and +200 on the goal square. These are divided by 100 for
        comparison to Q values on the synaptic permanence scale."]
   [:p "The action layer columns are interpreted to produce an
        action. 10 columns are allocated to each of the four
        directions of movement, and the direction with most active
        columns is used to move the agent."]
   [:p "The input is the location of the agent via coordinate
        encoder, plus the last movement as distal input."]
   [:p "This example is episodic: when the agent reaches either the
        goal or a hazard it is returned to the starting point. Success
        is indicated by the agent following a direct path to the goal
        square."]

   [:h3 "HTM model"]
   [bind-fields config-template config]
   ]
  )

(defn ^:export init
  []
  (reagent/render [main/comportexviz-app model-tab world-pane into-sim]
                  (dom/getElement "comportexviz-app"))
  (swap! main/viz-options assoc-in [:drawing :display-mode] :two-d)
  (set-model!)
  (feed-world!))
