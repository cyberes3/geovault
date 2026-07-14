/**
 * Public shape of `MobileMapDrawer`'s `expose()`d instance, as seen through a template ref from
 * `LiveTrackView`/`WorldShareView`/`useMobileView`. Vue auto-unwraps exposed refs when accessed
 * through the component's public instance, so consumers see plain values (not `Ref`s) here.
 */
export interface MobileMapDrawerExposed {
  collapseToPeek: () => void;
  isDrawerAtPeek: boolean;
  heightPx: number;
  snapPx: number[];
}
