import { Easing } from "react-native-reanimated";

/**
 * Motion tokens as Reanimated configs. Springs are tuned close to critically damped:
 * movement settles with at most a hint of overshoot so screens never look jumpy.
 * Effects springs (color, opacity) never overshoot.
 */
const spring = (stiffness: number, dampingRatio: number) => ({
  stiffness,
  damping: dampingRatio * 2 * Math.sqrt(stiffness),
  mass: 1,
});

export const Springs = {
  spatialFast: spring(900, 0.78),
  spatial: spring(500, 0.9),
  spatialSlow: spring(250, 0.92),
  effectsFast: spring(3800, 1),
  effects: spring(1600, 1),
};

/** M3 emphasized-decelerate easing for things entering the screen. */
export const emphasizedDecelerate = Easing.bezier(0.05, 0.7, 0.1, 1);
