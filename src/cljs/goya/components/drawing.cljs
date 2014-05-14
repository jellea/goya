(ns goya.components.drawing
  (:require-macros [cljs.core.async.macros :refer [go]])
	(:require [om.core :as om :include-macros true]
            [om.dom :as omdom :include-macros true]
            [goog.events :as events]
            [goya.components.geometry :as geometry]
            [goya.guistate :as guistate]
            [clojure.set :as sets]
            [cljs.core.async :refer [put! chan <! alts!]]))


;; =============================================================================
;; HERE BE DRAGONS
;; Needs a serious refactoring. You can tell I'm not speaking idiomatic
;; Clojure yet. Idiotmatic, perhaps.




;; =============================================================================
;; This is a bit of a hack. We hard code a canvas element into the HTML document
;; so we are able to draw the 'preview' of the stroke while the user is moving
;; the mouse quickly, and don't need to constantly re-render and mount a canvas
;; element on each call to render

(def preview-canvas-elem (. js/document (getElementById "preview-canvas")))

;; =============================================================================
;; it seems like it's too slow to use component local state for this. Hence,
;; it's living on its own in this external atom. Needs a second look.

(def visited-pixels (atom []))

(defn visit-pixel [index]
  (swap! visited-pixels conj index))

(defn forget-visited-pixels []
  (reset! visited-pixels []))

(defn assoc-all [v ks value]
  (reduce #(assoc %1 %2 value) v ks))

(defn commit-stroke [app]
  (let [paint-color (get-in @app [:tools :paint-color])
        old-image (get-in @app [:main-app :image-data])
        new-image (assoc-all old-image @visited-pixels paint-color)
        undo-list (get-in @app [:main-app :undo-history])
        paint-tool (get-in @app [:tools :paint-tool])]

    (om/update! app [:main-app :image-data] (vec new-image))

    (when (= paint-tool :pencil)
      (om/transact! app
                    [:main-app :undo-history]
                    #(conj % {:action (str "Painted Stroke") :icon "pencil"})
                    :add-to-undo))
    (when (= paint-tool :box)
      (om/transact! app
                    [:main-app :undo-history]
                    #(conj % {:action (str "Painted Rectangle") :icon "edit"})
                    :add-to-undo))
    (when (= paint-tool :fill)
      (om/transact! app
                    [:main-app :undo-history]
                    #(conj % {:action (str "Flood Filled") :icon "bucket"})
                    :add-to-undo))
    (forget-visited-pixels)))


;; =============================================================================

(defn preview-pix-coords [mouse-x mouse-y zoom-factor]
  (let [pixel-size zoom-factor
        x (quot mouse-x pixel-size)
        y (quot mouse-y pixel-size)]
    [(* x pixel-size) (* y pixel-size)]))

(defn visit-pixels-for-rect-tool [doc-x doc-y]
  (let [[orig-x orig-y] (get-in @guistate/transient-state [:mouse-down-pos])
        [x y nx ny] (geometry/normalize-rect orig-x orig-y doc-x doc-y)]
    (doseq [dx (range x nx)]
      (doseq [dy (range y ny)]
        (visit-pixel (geometry/flatten-to-index dx dy 64))))))

(defn unpack-event [event]
  [(.-offsetX event) (.-offsetY event)])


;; =============================================================================
;; Pick color

(defn pick-color [app doc-x doc-y]
  (let [index (geometry/flatten-to-index doc-x doc-y 64)
        color (nth (get-in @app [:main-app :image-data]) index)]
    (om/update! app [:tools :paint-color] color)))


;; =============================================================================
;; The Ned Flanders function

(defn neighborinos [idx image-data color]
  (let [left   (dec idx)
        right  (inc idx)
        top    (- idx 64)
        bottom (+ idx 64)
        result [left right top bottom]
        valid-result (filter #(and (>= % 0) (< % 4096)) result)]
    (filter #(= (nth image-data %) color) valid-result)))


;; =============================================================================
;; Credits to:
;; http://stevelosh.com/blog/2012/10/caves-of-clojure-07-1/

(defn flood [idx image-data color]
  (loop [connected #{}
         to-connect #{idx}]
    (if (empty? to-connect)
      connected
      (let [current (first to-connect)
            connected (conj connected current)
            to-connect (disj to-connect current)
            candidates (set (neighborinos current image-data color))
            to-connect (sets/union to-connect (sets/difference candidates connected))]
        (recur connected to-connect)))))


(defn visit-pixels-for-fill-tool [doc-x doc-y image-data]
  (let [[orig-x orig-y] (get-in @guistate/transient-state [:mouse-down-pos])
        idx (geometry/flatten-to-index orig-x orig-y 64)
        color (nth image-data idx)
        fill-target (flood idx image-data color)]
    (doseq [i fill-target]
      (visit-pixel i))))


;; =============================================================================

(defn clear-preview-canvas []
  (set! (.-width preview-canvas-elem) (.-width preview-canvas-elem)))


;; =============================================================================
;; Some routines specific to the move/selection tool
;; Pro: Learned a bit about clojure list comprehensions
;; Con: This file is turning into something like a little monster

(defn assoc-map [img-vector indices-to-colors]
  (loop [img img-vector
         keys-to-assoc (vec (keys indices-to-colors))]
    (if (empty? keys-to-assoc)
      img
      (let [key-to-assoc (peek keys-to-assoc)
            keys-to-assoc (pop keys-to-assoc)
            color (indices-to-colors key-to-assoc)
            img (if (not (= key-to-assoc :clipped))
                  (assoc img key-to-assoc color)
                  img)]
        (recur img keys-to-assoc)))))


(defn create-sub-image-for-rect [rect color]
  (let [width (geometry/rect-width rect)
        height (geometry/rect-height rect)
        pixel-count (* width height)
        image-data (vec (take pixel-count (repeat color)))]
    {:width width
     :height height
     :image-data image-data}))


(defn paste-image [app owner doc-x doc-y sub-image]
  (let [main-image (get-in @app [:main-app :image-data])
        main-image-width (get-in @app [:main-app :canvas-width])
        pixels-in-sub-image (count (:image-data sub-image))
        indices (for [i (range 0 pixels-in-sub-image)
                      :let [x (mod i (:width sub-image))
                            y (quot i (:width sub-image))]]
                      [(if (geometry/contains-point [0 0 63 63] [(+ x doc-x) (+ y doc-y)])
                           (geometry/flatten-to-index (+ x doc-x) (+ y doc-y) main-image-width)
                           :clipped)])
        flat-indices (vec (flatten (vec indices)))
        indices-to-colors (zipmap flat-indices (:image-data sub-image))
        new-image (assoc-map main-image indices-to-colors)]
    (om/update! app [:main-app :image-data] (vec new-image))))


(defn blit-sub-image [app sub-image xoff yoff]
  (let [canvas preview-canvas-elem
        context (.getContext canvas "2d")
        width (get-in @app [:main-app :canvas-width])
        height (get-in @app [:main-app :canvas-height])
        zoom-factor (get-in @app [:zoom-factor])
        pixel-size zoom-factor
        sub-image-pixel-count (count (:image-data sub-image))]
    (dotimes [x sub-image-pixel-count]
      (let [pix-x (* (mod x (:width sub-image)) pixel-size)
            pix-y (* (quot x (:width sub-image)) pixel-size)
            color (nth (:image-data sub-image) x)
            x-with-offset (+ pix-x (* xoff pixel-size))
            y-with-offset (+ pix-y (* yoff pixel-size))]
        (set! (.-fillStyle context) color)
        (.fillRect context x-with-offset y-with-offset pixel-size pixel-size)))))


(defn clip-sub-image [image rect]
  (let [[x1 y1 x2 y2] rect
        width (geometry/rect-width rect)
        height (geometry/rect-height rect)
        main-image-width 64
        sub-image-indices(for [y (range y1 y2)
                               x (range x1 x2)
                               :let [i (geometry/flatten-to-index x y main-image-width)]]
                               i)
        sub-image-data (vec (map #(nth image %) sub-image-indices))]
      {:width width
       :height height
       :image-data sub-image-data}))


;; =============================================================================

(defn clear-selection-state [owner]
  (om/set-state! owner :selection [0 0 0 0])
  (om/set-state! owner :mouse-offset-in-selection [0 0])
  (om/set-state! owner :selection-image {})
  (om/set-state! owner :user-is-moving-selection false))


(defn make-selection [app owner doc-x doc-y]
  (let [[orig-x orig-y] (get-in @guistate/transient-state [:mouse-down-pos])
        selection-rect (geometry/normalize-rect orig-x orig-y doc-x doc-y)
        main-image (get-in @app [:main-app :image-data])]
    (om/set-state! owner :selection selection-rect)
    (om/set-state! owner :selection-image (clip-sub-image main-image selection-rect))))


(defn draw-selection [app owner]
   (let [preview-context (.getContext preview-canvas-elem "2d")
         [x1 y1 x2 y2] (om/get-state owner :selection)
         zoom-factor (get-in app [:zoom-factor])]
     (clear-preview-canvas)
     (set! (.-strokeStyle preview-context) "#ffffff")
     (.setLineDash preview-context #js [5])
     (.rect preview-context
         (* x1 zoom-factor)
         (* y1 zoom-factor)
         (* (- x2 x1) zoom-factor)
         (* (- y2 y1) zoom-factor))
     (.stroke preview-context)))


;; =============================================================================

(defn paint-canvas-mouse-pos [app owner event]
(let [[x y] (unpack-event event)
      zoom-factor (get-in @app [:zoom-factor])
      [doc-x doc-y] (geometry/screen-to-doc x y zoom-factor)
      pixel-size zoom-factor
      doc-canvas-width (get-in @app [:main-app :canvas-width])
      doc-canvas-height (get-in @app [:main-app :canvas-height])
      doc-index (geometry/flatten-to-index doc-x doc-y doc-canvas-width)
      paint-color (get-in @app [:tools :paint-color])
      paint-tool (get-in @app [:tools :paint-tool])
      preview-context (.getContext preview-canvas-elem "2d")
      [mouse-down-x mouse-down-y] (get-in @guistate/transient-state [:mouse-down-pos])]
  (when (= paint-tool :pencil)
    (set! (.-fillStyle preview-context) paint-color)
    (.fillRect preview-context (* doc-x zoom-factor) (* doc-y zoom-factor) pixel-size pixel-size)
    (visit-pixel doc-index))

  (when (= paint-tool :box)
    (set! (.-width preview-canvas-elem) (.-width preview-canvas-elem))
    (set! (.-fillStyle preview-context) paint-color)

    (let [adjusted-doc-x (inc doc-x)
          adjusted-doc-y (inc doc-y)]
      (.fillRect preview-context
         (* mouse-down-x zoom-factor)
         (* mouse-down-y zoom-factor)
         (* (- adjusted-doc-x mouse-down-x) zoom-factor)
         (* (- adjusted-doc-y mouse-down-y) zoom-factor))))

  (when (= paint-tool :selection)
    (let [user-is-moving-selection (om/get-state owner :user-is-moving-selection)]
      (when (not user-is-moving-selection)
        (clear-preview-canvas)
        (set! (.-fillStyle preview-context) "rgba(127,127,127,0.3)")
        (let [adjusted-doc-x (inc doc-x)
              adjusted-doc-y (inc doc-y)]
          (.fillRect preview-context
             (* mouse-down-x zoom-factor)
             (* mouse-down-y zoom-factor)
             (* (- adjusted-doc-x mouse-down-x) zoom-factor)
             (* (- adjusted-doc-y mouse-down-y) zoom-factor))
          (set! (.-strokeStyle preview-context) "#ffffff")
          (.setLineDash preview-context #js [5])
          (.rect preview-context
            (* mouse-down-x zoom-factor)
            (* mouse-down-y zoom-factor)
            (* (- adjusted-doc-x mouse-down-x) zoom-factor)
            (* (- adjusted-doc-y mouse-down-y) zoom-factor))
          (.stroke preview-context)))
      (when user-is-moving-selection
        (let [[offset-x offset-y] (om/get-state owner :mouse-offset-in-selection)
              [x1 y1 x2 y2] (om/get-state owner :selection)
              blit-x (- doc-x offset-x)
              blit-y (- doc-y offset-y)]
          (clear-preview-canvas)
          (set! (.-fillStyle preview-context) (get-in @app [:tools :paint-color]))
          (.fillRect preview-context
             (* x1 zoom-factor)
             (* y1 zoom-factor)
             (* (- x2 x1) zoom-factor)
             (* (- y2 y1) zoom-factor))
          (blit-sub-image app (om/get-state owner :selection-image) blit-x blit-y)))))))


;; =============================================================================

(defn canvas-painting-component [app owner]
  (reify
    om/IInitState
      (init-state [_]
        {:mouse-chan (chan)
         :user-is-moving-selection false
         :selection [0 0 0 0]
         :selection-image {}
         :mouse-offset-in-selection [0 0]})


    om/IDidUpdate
      (did-update [_ _ _]
          (clear-preview-canvas)
          (when (= (get-in app [:tools :paint-tool]) :selection)
            (draw-selection app owner)))

    om/IWillMount
    (will-mount [_]
       (let [mouse-chan (om/get-state owner :mouse-chan)]
        (go
          (loop []
            (let [e (<! mouse-chan)
                  event-type (.-type e)
                  [x y] (unpack-event e)
                  zoom-factor (get-in @app [:zoom-factor])
                  [doc-x doc-y] (geometry/screen-to-doc x y zoom-factor)
                  paint-tool (get-in @app [:tools :paint-tool])]

                  (when (= event-type "mousedown")
                    (when (not (= paint-tool :selection))
                      (reset! guistate/transient-state
                              (assoc @guistate/transient-state :user-is-drawing true))
                      (reset! guistate/transient-state
                              (assoc @guistate/transient-state :mouse-down-pos [doc-x doc-y]))
                      (paint-canvas-mouse-pos app owner e))
                    (when (= paint-tool :selection)
                      (let [mouse-is-within-selection (geometry/contains-point
                                                         (om/get-state owner :selection)
                                                         [doc-x doc-y])
                            [x1 y1 x2 y2] (om/get-state owner :selection)
                            offset-in-selection [(- doc-x x1) (- doc-y y1)]]
                         (when mouse-is-within-selection
                               (om/set-state! owner :user-is-moving-selection true)
                               (om/set-state! owner :mouse-offset-in-selection offset-in-selection)))
                      (reset! guistate/transient-state
                              (assoc @guistate/transient-state :user-is-drawing true))
                      (reset! guistate/transient-state
                              (assoc @guistate/transient-state :mouse-down-pos [doc-x doc-y]))
                      (paint-canvas-mouse-pos app owner e)))

                  (when (= event-type "mouseup")
                    (when (= paint-tool :box)
                      (visit-pixels-for-rect-tool doc-x doc-y))
                    (when (= paint-tool :fill)
                      (visit-pixels-for-fill-tool doc-x doc-y (get-in @app [:main-app :image-data])))
                    (when (= paint-tool :picker)
                      (pick-color app doc-x doc-y))
                    (when (and (= paint-tool :selection) (not (om/get-state owner :user-is-moving-selection)))
                      (make-selection app owner doc-x doc-y))
                    (when (and (= paint-tool :selection) (om/get-state owner :user-is-moving-selection))
                      (let [[xOff yOff] (om/get-state owner :mouse-offset-in-selection)
                            [x1 y1 x2 y2] (om/get-state owner :selection)
                            paste-x (- doc-x xOff)
                            paste-y (- doc-y yOff)
                            backfill (create-sub-image-for-rect (om/get-state owner :selection)
                                                                (get-in @app [:tools :paint-color]))]
                      (paste-image app owner x1 y1 backfill)
                      (paste-image app owner paste-x paste-y (om/get-state owner :selection-image)))
                      (clear-selection-state owner)
                      (om/transact! app
                        [:main-app :undo-history]
                        #(conj % {:action (str "Moved pixels") :icon "move"})
                        :add-to-undo))
                    (commit-stroke app)
                    (reset! guistate/transient-state
                            (assoc @guistate/transient-state :user-is-drawing false)))

                  (when (and (= event-type "mousemove") (:user-is-drawing @guistate/transient-state))
                    (paint-canvas-mouse-pos app owner e))

                  (when (= event-type "mousemove")
                    (reset! guistate/transient-state (assoc @guistate/transient-state :mouse-pos [doc-x doc-y])))

          (recur))))))

    ;; =============================================================================
    ;; I shouldn't have to listen to the events like this, but if I simply setup
    ;; the listeners in the render function, I get bogus events

    om/IDidMount
      (did-mount [_]
        (let [painter-watcher (om/get-node owner "painter-watcher-ref")
              mouse-chan (om/get-state owner :mouse-chan)]
          (events/listen painter-watcher "mousemove" #(put! mouse-chan %))
          (events/listen painter-watcher "mousedown" #(put! mouse-chan %))
          (events/listen painter-watcher "mouseup" #(put! mouse-chan %))))

    om/IRenderState
      (render-state [this {:keys [mouse-chan]}]
        (let [doc-canvas-width (get-in app [:main-app :canvas-width])
              doc-canvas-height (get-in app [:main-app :canvas-height])
              zoom-factor (get-in app [:zoom-factor])
              screen-canvas-width (* doc-canvas-width zoom-factor)
              screen-canvas-height (* doc-canvas-height zoom-factor)]
        (set! (.-width preview-canvas-elem) screen-canvas-width)
        (set! (.-height preview-canvas-elem) screen-canvas-height)
        (omdom/div #js {:id "canvas-watcher" :className "canvas-watcher"}
          (omdom/div #js {:id "painter-watcher"
                        :style #js {:width screen-canvas-width
                                    :height screen-canvas-height}
                        :ref "painter-watcher-ref"}))))))
