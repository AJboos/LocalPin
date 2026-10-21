package local.pin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable WGS84 route; the closing segment is implicit, not another editable vertex. */
final class RoutePlan {
    static final int MAX_POINTS=200;
    private static final double EARTH_METERS=6371008.8;
    private static final double DUPLICATE_METERS=0.000001;
    final List<SimulationEngine.Point> points;
    final boolean closed;
    final double minSpeedMps,maxSpeedMps,distanceLimitMeters,lengthMeters;
    final long durationLimitMillis;
    private final Segment[] segments;
    private final double[] ends;

    RoutePlan(List<SimulationEngine.Point> input,boolean closed,double minSpeedMps,
              double maxSpeedMps,long durationLimitMillis,double distanceLimitMeters) {
        if(input==null||input.size()<2||input.size()>MAX_POINTS)
            throw new IllegalArgumentException("Route needs 2..200 points");
        if(!Double.isFinite(minSpeedMps)||!Double.isFinite(maxSpeedMps)
                ||minSpeedMps<=0||minSpeedMps>maxSpeedMps||maxSpeedMps>100)
            throw new IllegalArgumentException("Invalid speed range");
        if(durationLimitMillis<0||!Double.isFinite(distanceLimitMeters)||distanceLimitMeters<0
                ||durationLimitMillis>0&&distanceLimitMeters>0)
            throw new IllegalArgumentException("Choose only one nonnegative route limit");
        ArrayList<SimulationEngine.Point> copy=new ArrayList<>();
        for(SimulationEngine.Point point:input) {
            if(point==null||!MapMath.valid(point.latitude,point.longitude))
                throw new IllegalArgumentException("Invalid route point");
            if(!copy.isEmpty()&&distance(copy.get(copy.size()-1),point)<=DUPLICATE_METERS)
                throw new IllegalArgumentException("Adjacent route points must differ");
            copy.add(new SimulationEngine.Point(point.latitude,point.longitude));
        }
        if(closed&&distance(copy.get(0),copy.get(copy.size()-1))<=DUPLICATE_METERS)
            copy.remove(copy.size()-1);
        if(closed) {
            ArrayList<SimulationEngine.Point> unique=new ArrayList<>();
            for(SimulationEngine.Point point:copy) {
                boolean seen=false;
                for(SimulationEngine.Point other:unique)
                    if(distance(point,other)<=DUPLICATE_METERS){seen=true;break;}
                if(!seen)unique.add(point);
            }
            if(unique.size()<3)throw new IllegalArgumentException("Closed route needs 3 distinct points");
        }
        this.closed=closed;
        this.minSpeedMps=minSpeedMps;this.maxSpeedMps=maxSpeedMps;
        this.durationLimitMillis=durationLimitMillis;this.distanceLimitMeters=distanceLimitMeters;
        points=Collections.unmodifiableList(copy);
        segments=new Segment[closed?copy.size():copy.size()-1];
        ends=new double[segments.length];
        double total=0;
        for(int i=0;i<segments.length;i++) {
            Segment segment=new Segment(copy.get(i),copy.get((i+1)%copy.size()));
            if(segment.length<=DUPLICATE_METERS)throw new IllegalArgumentException("Empty route segment");
            segments[i]=segment;total+=segment.length;ends[i]=total;
        }
        if(!Double.isFinite(total)||total<1)throw new IllegalArgumentException("Route must be at least 1 meter");
        if(!closed&&distanceLimitMeters>total)
            throw new IllegalArgumentException("Open route limit exceeds route length");
        lengthMeters=total;
    }

    static double distance(SimulationEngine.Point a,SimulationEngine.Point b) {
        if(a==null||b==null)throw new IllegalArgumentException("Missing point");
        double lat1=Math.toRadians(a.latitude),lat2=Math.toRadians(b.latitude);
        double dlat=lat2-lat1,dlon=Math.toRadians(b.longitude-a.longitude);
        double x=Math.sin(dlat/2),y=Math.sin(dlon/2);
        double h=Math.max(0,Math.min(1,x*x+Math.cos(lat1)*Math.cos(lat2)*y*y));
        return EARTH_METERS*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
    }

    SimulationEngine.Point pointAt(double cumulativeMeters) {
        double along=normalize(cumulativeMeters);
        if(along==0)return points.get(0);
        if(!closed&&along>=lengthMeters)return points.get(points.size()-1);
        int index=segmentAt(along);
        return segments[index].point((along-(index==0?0:ends[index-1]))/segments[index].length);
    }

    double bearingAt(double cumulativeMeters) {
        double along=normalize(cumulativeMeters);
        int index=segmentAt(along);
        return segments[index].bearing((along-(index==0?0:ends[index-1]))/segments[index].length);
    }

    private double normalize(double meters) {
        if(!Double.isFinite(meters)||meters<0)throw new IllegalArgumentException("Invalid route progress");
        return closed?meters%lengthMeters:Math.min(meters,lengthMeters);
    }

    private int segmentAt(double meters) {
        int low=0,high=ends.length-1;
        while(low<high) {
            int middle=(low+high)>>>1;
            if(meters<ends[middle])high=middle;else low=middle+1;
        }
        return low;
    }

    private static final class Segment {
        final double[] start,tangent;
        final double angle,length;
        Segment(SimulationEngine.Point a,SimulationEngine.Point b) {
            start=vector(a);double[] end=vector(b);
            double dot=dot(start,end),cross=norm(cross(start,end));
            angle=Math.atan2(cross,Math.max(-1,Math.min(1,dot)));
            length=distance(a,b);
            double[] direction=new double[]{end[0]-dot*start[0],end[1]-dot*start[1],end[2]-dot*start[2]};
            if(dot<0&&norm(direction)<1e-12) {
                // Exactly antipodal points have multiple shortest arcs. Choose a
                // deterministic orthogonal tangent rather than divide by sin(pi).
                double[] axis=Math.abs(start[2])<0.9?new double[]{0,0,1}:new double[]{1,0,0};
                double projection=dot(axis,start);
                direction=new double[]{axis[0]-projection*start[0],axis[1]-projection*start[1],axis[2]-projection*start[2]};
            }
            double size=norm(direction);
            tangent=new double[]{direction[0]/size,direction[1]/size,direction[2]/size};
        }
        double[] at(double fraction) {
            double theta=angle*Math.max(0,Math.min(1,fraction)),c=Math.cos(theta),s=Math.sin(theta);
            return new double[]{start[0]*c+tangent[0]*s,start[1]*c+tangent[1]*s,start[2]*c+tangent[2]*s};
        }
        SimulationEngine.Point point(double fraction) {
            double[] p=at(fraction);
            return new SimulationEngine.Point(Math.toDegrees(Math.atan2(p[2],Math.hypot(p[0],p[1]))),
                    Math.toDegrees(Math.atan2(p[1],p[0])));
        }
        double bearing(double fraction) {
            double theta=angle*Math.max(0,Math.min(1,fraction)),c=Math.cos(theta),s=Math.sin(theta);
            double[] p=at(fraction);
            double[] v=new double[]{-start[0]*s+tangent[0]*c,-start[1]*s+tangent[1]*c,-start[2]*s+tangent[2]*c};
            double lat=Math.atan2(p[2],Math.hypot(p[0],p[1])),lon=Math.atan2(p[1],p[0]);
            double east=-v[0]*Math.sin(lon)+v[1]*Math.cos(lon);
            double north=-v[0]*Math.sin(lat)*Math.cos(lon)-v[1]*Math.sin(lat)*Math.sin(lon)+v[2]*Math.cos(lat);
            double bearing=Math.toDegrees(Math.atan2(east,north));
            return (bearing+360)%360;
        }
        static double[] vector(SimulationEngine.Point p) {
            double lat=Math.toRadians(p.latitude),lon=Math.toRadians(p.longitude),c=Math.cos(lat);
            return new double[]{c*Math.cos(lon),c*Math.sin(lon),Math.sin(lat)};
        }
        static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
        static double norm(double[] a){return Math.sqrt(dot(a,a));}
        static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    }
}
